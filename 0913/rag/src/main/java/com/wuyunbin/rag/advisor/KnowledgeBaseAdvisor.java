package com.wuyunbin.rag.advisor;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClientRequest;
import org.springframework.ai.chat.client.ChatClientResponse;
import org.springframework.ai.chat.client.advisor.api.CallAdvisor;
import org.springframework.ai.chat.client.advisor.api.CallAdvisorChain;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.MessageType;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.core.io.Resource;
import org.springframework.util.StringUtils;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * RAG 检索增强 advisor：在调用模型之前先去向量库检索资料，并把资料拼进 system 消息。
 *
 * <h3>详细召回日志</h3>
 * <p>本类会把每次检索的完整信息写进独立的 {@code RAG_RETRIEVAL} logger
 * （落盘为 {@code logs/rag-retrieval.log}），包括每条命中的<b>相似度分数、排名、章节名、
 * 完整正文</b>。</p>
 *
 * <p>为什么这件事很重要：排查"某句话为什么召不回"时，只看"命中 N 条"是没用的 ——
 * 你需要看到分数和内容本身，才能判断是「没检索到」还是「检索到了但排序不对」。
 * 之前正是因为看不到这些，我们不得不另外写脚本去手工比对 embedding 相似度。</p>
 *
 * <h3>关于 order（很重要，放反了会损坏会话记忆）</h3>
 * <ul>
 *   <li>{@code MessageChatMemoryAdvisor} 的 order 是 {@code Advisor.DEFAULT_CHAT_MEMORY_PRECEDENCE_ORDER}
 *       （约 -2.1e9，几乎最外层）</li>
 *   <li>{@code ChatModelCallAdvisor} 的 order 是 {@code Integer.MAX_VALUE}（最内层）</li>
 * </ul>
 * <p>{@code MessageChatMemoryAdvisor.before()} 会把<b>最后一条用户消息</b>写进会话记忆。
 * 本 advisor 必须排在其内层，否则写进记忆的会是"用户原话 + 检索资料"，
 * 导致记忆被资料撑爆并污染后续轮次。</p>
 */
public class KnowledgeBaseAdvisor implements CallAdvisor {

    private static final Logger log = LoggerFactory.getLogger(KnowledgeBaseAdvisor.class);

    /** 召回详情专用 logger，由 logback 路由到 logs/rag-retrieval.log（不冒泡到主日志） */
    private static final Logger detail = LoggerFactory.getLogger("RAG_RETRIEVAL");

    private static final String CONTEXT_PLACEHOLDER = "{{context}}";
    private static final String NO_HIT_TEXT = "(未检索到相关资料)";

    /** 召回日志详细程度 */
    public static final String LEVEL_OFF = "off";
    public static final String LEVEL_BRIEF = "brief";
    public static final String LEVEL_FULL = "full";
    public static final String LEVEL_PROMPT = "prompt";

    private final VectorStore vectorStore;
    private final String systemPromptTemplate;
    private final int topK;
    private final double similarityThreshold;
    private final boolean enabled;
    private final int order;
    private final String collectionName;

    /** off | brief | full | prompt */
    private final String detailLevel;
    /** 单条正文打印上限，0 表示不截断 */
    private final int detailMaxChars;
    /** 低分告警阈值，<=0 表示不告警（当前嵌入模型中文分数普遍偏低，默认关闭避免刷屏） */
    private final double lowScoreThreshold;

    public KnowledgeBaseAdvisor(VectorStore vectorStore,
                                Resource systemPromptResource,
                                int topK,
                                double similarityThreshold,
                                boolean enabled,
                                int order,
                                String collectionName,
                                String detailLevel,
                                int detailMaxChars,
                                double lowScoreThreshold) {
        this.vectorStore = vectorStore;
        this.systemPromptTemplate = readTemplate(systemPromptResource);
        this.topK = topK;
        this.similarityThreshold = similarityThreshold;
        this.enabled = enabled;
        this.order = order;
        this.collectionName = collectionName;
        this.detailLevel = StringUtils.hasText(detailLevel) ? detailLevel.trim().toLowerCase() : LEVEL_FULL;
        this.detailMaxChars = detailMaxChars;
        this.lowScoreThreshold = lowScoreThreshold;
    }

    // ------------------------------------------------------------------

    @Override
    public ChatClientResponse adviseCall(ChatClientRequest request, CallAdvisorChain chain) {
        if (!enabled) {
            log.debug("RAG 已关闭(rag.chat.rag.enabled=false)，本次不检索、不注入手册角色");
            return chain.nextCall(request);
        }

        String query = lastUserText(request.prompt().getInstructions());
        if (!StringUtils.hasText(query)) {
            return chain.nextCall(request);
        }

        String conversationId = conversationId(request);
        long start = System.currentTimeMillis();
        List<Document> hits = retrieve(query);
        long retrieveMs = System.currentTimeMillis() - start;

        logRetrieval(query, conversationId, hits, retrieveMs);

        String context = buildContext(hits);
        String systemText = systemPromptTemplate.replace(CONTEXT_PLACEHOLDER, context);

        log.info("RAG 检索: query=\"{}\" 命中={} 条 资料={} 字 耗时={}ms",
                abbreviate(query, 60), hits.size(), context.length(), retrieveMs);

        List<Message> messages = new ArrayList<>();
        messages.add(new SystemMessage(systemText));
        for (Message message : request.prompt().getInstructions()) {
            // 原 system 消息被上面的新 system 取代，其余原样保留
            if (message.getMessageType() != MessageType.SYSTEM) {
                messages.add(message);
            }
        }

        if (isPromptLevel()) {
            logFinalPrompt(systemText, messages);
        }

        ChatClientRequest augmented = request.mutate()
                .prompt(request.prompt().mutate().messages(messages).build())
                .build();

        return chain.nextCall(augmented);
    }

    // ------------------------------------------------------------------
    // 召回详情日志
    // ------------------------------------------------------------------

    private void logRetrieval(String query, String conversationId, List<Document> hits, long retrieveMs) {
        if (LEVEL_OFF.equals(detailLevel)) {
            return;
        }

        detail.info("==================== 检索开始 ====================");
        detail.info("会话: {}", conversationId);
        detail.info("查询原文: {}", query);
        detail.info("参数: topK={} 相似度阈值={} 集合={}", topK, similarityThreshold, collectionName);
        detail.info("结果: 命中 {} 条, 检索耗时 {}ms", hits.size(), retrieveMs);

        if (hits.isEmpty()) {
            detail.info("(无命中 —— 知识库中没有相似片段，或相似度阈值过高)");
            detail.info("==================== 检索结束 ====================");
            return;
        }

        Double max = null;
        Double min = null;
        int rank = 0;
        for (Document doc : hits) {
            rank++;
            Double score = doc.getScore();
            if (score != null) {
                max = (max == null || score > max) ? score : max;
                min = (min == null || score < min) ? score : min;
            }
            Object heading = doc.getMetadata().get("heading");
            String text = doc.getText();
            int len = text == null ? 0 : text.length();

            detail.info("--- #{} 排名={} 分数={} 章节=\"{}\" 长度={} 字 ---",
                    rank, rank, formatScore(score),
                    heading == null ? "" : heading.toString(), len);

            if (isFullLevel() && StringUtils.hasText(text)) {
                String body = detailMaxChars > 0 && len > detailMaxChars
                        ? text.substring(0, detailMaxChars) + "…(已截断," + len + "字)"
                        : text;
                // 每行加 "> " 前缀：多行正文仍可逐行 grep，不会把日志结构搞乱
                for (String line : body.split("\\R")) {
                    detail.info("> {}", line);
                }
            }
        }

        detail.info("分数区间: 最高={} 最低={}", formatScore(max), formatScore(min));

        if (lowScoreThreshold > 0 && max != null && max < lowScoreThreshold) {
            log.warn("RAG 召回质量偏低: 最高相似度仅 {} < 阈值 {}", formatScore(max), lowScoreThreshold);
            detail.warn("⚠ 最高相似度仅 {} < 阈值 {}，本次检索可能未命中", formatScore(max), lowScoreThreshold);
        }
    }

    private void logFinalPrompt(String systemText, List<Message> messages) {
        detail.info("==================== 最终 system 消息 ({} 字) ====================", systemText.length());
        for (String line : systemText.split("\\R")) {
            detail.info("S> {}", line);
        }
        detail.info("==================== 消息序列 (共 {} 条) ====================", messages.size());
        for (Message message : messages) {
            String text = message.getText();
            detail.info("  {} : {} 字", message.getMessageType().name(), text == null ? 0 : text.length());
        }
        detail.info("==================== prompt 结束 ====================");
    }

    // ------------------------------------------------------------------

    private boolean isFullLevel() {
        return LEVEL_FULL.equals(detailLevel) || LEVEL_PROMPT.equals(detailLevel);
    }

    private boolean isPromptLevel() {
        return LEVEL_PROMPT.equals(detailLevel);
    }

    private String conversationId(ChatClientRequest request) {
        Object value = request.context().get(ChatMemory.CONVERSATION_ID);
        return value == null ? "-" : value.toString();
    }

    private String formatScore(Double score) {
        return score == null ? "n/a" : String.format("%.4f", score);
    }

    /** 取最后一条用户消息作为检索 query。 */
    private String lastUserText(List<Message> instructions) {
        for (int i = instructions.size() - 1; i >= 0; i--) {
            Message message = instructions.get(i);
            if (message.getMessageType() == MessageType.USER) {
                return message.getText();
            }
        }
        return null;
    }

    private List<Document> retrieve(String query) {
        SearchRequest.Builder builder = SearchRequest.builder().query(query).topK(topK);
        if (similarityThreshold > 0) {
            builder.similarityThreshold(similarityThreshold);
        }
        try {
            List<Document> hits = vectorStore.similaritySearch(builder.build());
            return hits == null ? List.of() : hits;
        } catch (Exception e) {
            // 检索失败不应让整个对话挂掉，降级为「无资料」继续回答
            log.warn("向量检索失败，本次对话将不带参考资料继续", e);
            detail.warn("向量检索异常: {}", e.toString());
            return List.of();
        }
    }

    /**
     * 拼装参考资料文本，按正文去重。
     * 知识库里可能存在同一段落被多次导入的情况，重复内容会白占上下文额度。
     */
    private String buildContext(List<Document> hits) {
        if (hits.isEmpty()) {
            return NO_HIT_TEXT;
        }
        Set<String> seen = new LinkedHashSet<>();
        StringBuilder sb = new StringBuilder();
        int index = 0;
        int dropped = 0;
        for (Document doc : hits) {
            String text = doc.getText();
            if (!StringUtils.hasText(text)) {
                continue;
            }
            if (!seen.add(text.trim())) {
                dropped++;
                continue;
            }
            index++;
            Object heading = doc.getMetadata().get("heading");
            sb.append("[").append(index).append("]");
            if (heading != null && StringUtils.hasText(heading.toString())) {
                sb.append(" 章节：").append(heading.toString().trim());
            }
            sb.append("\n").append(text.trim()).append("\n\n");
        }

        if (!LEVEL_OFF.equals(detailLevel)) {
            detail.info("去重: 命中 {} 条 → 有效 {} 条（丢弃重复正文 {} 条）", hits.size(), index, dropped);
            detail.info("==================== 检索结束 ====================");
        }
        log.debug("参考资料去重: 命中 {} 条 -> 有效 {} 条(丢弃 {})", hits.size(), index, dropped);

        if (index == 0) {
            return NO_HIT_TEXT;
        }
        return sb.toString().trim();
    }

    private String abbreviate(String text, int max) {
        if (text == null) {
            return "";
        }
        return text.length() <= max ? text : text.substring(0, max) + "…";
    }

    private String readTemplate(Resource resource) {
        try {
            return resource.getContentAsString(StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException("无法读取 system prompt 模板: " + resource, e);
        }
    }

    @Override
    public String getName() {
        return "KnowledgeBaseAdvisor";
    }

    @Override
    public int getOrder() {
        return order;
    }
}
