package com.wuyunbin.rag.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.ai.vectorstore.filter.FilterExpressionBuilder;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * 知识库服务：文档读取 -> 标题分节/分块 -> 向量化 -> 写入 Milvus。
 */
@Service
public class KnowledgeBaseService {

    private static final Logger log = LoggerFactory.getLogger(KnowledgeBaseService.class);

    /** 默认导入文档：集美大学2025级新生入学手册 */
    public static final String DEFAULT_DOCUMENT_PATH =
            "C:\\path\\to\\jmu新生手册.txt";

    /** Markdown 风格标题行，例如 "# 学校基础信息"、"## 报到流程" */
    private static final Pattern HEADING_SPLIT = Pattern.compile("(?m)(?=^#{1,4} )");

    /** 单块目标最大字符数，超出按段落继续切分 */
    private static final int MAX_CHUNK_CHARS = 900;

    /**
     * 表格行：行首是 {@code |}。
     * 注意与校歌简谱区分 —— 简谱里的 {@code |} 是小节线，位于行中间。
     */
    private static final Pattern TABLE_ROW = Pattern.compile("^\\s*\\|");

    private final VectorStore vectorStore;
    private final DocumentCleaner cleaner;

    /**
     * 当前使用的 Milvus 集合名。
     *
     * <p><b>必须来自配置，不能写成硬编码常量。</b>
     * 原来这里是 {@code public static final String collectionName = "jmu_handbook"}，
     * 结果在切到别的集合（如 {@code jmu_admission}）时出问题：
     * 写进块 metadata 的 {@code collection} 字段仍是旧名字，
     * 导致按 metadata 过滤的 {@code clear()} 语义错乱、接口响应也报错集合名。</p>
     */
    private final String collectionName;

    public KnowledgeBaseService(VectorStore vectorStore,
                                DocumentCleaner cleaner,
                                @Value("${spring.ai.vectorstore.milvus.collection-name:jmu_handbook}") String collectionName) {
        this.vectorStore = vectorStore;
        this.cleaner = cleaner;
        this.collectionName = collectionName;
    }

    /** 当前使用的集合名，供接口层与启动日志展示。 */
    public String collectionName() {
        return collectionName;
    }

    public String defaultDocumentPath() {
        return DEFAULT_DOCUMENT_PATH;
    }

    /**
     * 导入文档：读取 -> 按标题分节（超长再按段落切分）-> 逐块 embedding -> 写入 Milvus。
     *
     * @param path 文档绝对路径；为空/空白时使用默认新生手册
     * @return 实际写入的向量块数量
     */
    public int importDocument(String path) {
        String docPath = (path == null || path.isBlank()) ? DEFAULT_DOCUMENT_PATH : path;

        long start = System.currentTimeMillis();
        String raw = readText(docPath);

        // 清洗必须在分块之前：脏数据一旦写进向量库，检索出来的就是垃圾
        String content = raw;
        if (cleaner.isEnabled()) {
            DocumentCleaner.CleanResult cr = cleaner.cleanWithStats(raw);
            content = cr.cleaned();
            log.info("文档清洗: {} 字 → {} 字(删除 {} 字, 占 {}%), 其中校歌曲调 {} 行 / 目录 {} 行, 状态转移 {} 次",
                    cr.originalChars(), cr.cleanedChars(), cr.removedChars(), cr.removedPercent() + "%",
                    cr.songLinesRemoved(), cr.tocLinesRemoved(), cr.stateTransitions());
        } else {
            log.info("文档清洗: 已关闭(rag.kb.clean.enabled=false),使用原始文本");
        }

        List<Document> chunks = buildChunks(content, docPath);

        log.info("导入开始: 文档={} 正文={}字 切分={}块 collection={}",
                docPath, content.length(), chunks.size(), collectionName);

        vectorStore.add(chunks);

        log.info("导入完成: 写入 {} 块, 耗时 {}ms collection={}",
                chunks.size(), System.currentTimeMillis() - start, collectionName);
        return chunks.size();
    }

    /**
     * 清洗预览：不写库，只返回清洗前后的统计与结果，用于核对清洗规则是否正确。
     */
    public DocumentCleaner.CleanResult cleanPreview(String path) {
        String docPath = (path == null || path.isBlank()) ? DEFAULT_DOCUMENT_PATH : path;
        return cleaner.cleanWithStats(readText(docPath));
    }

    /**
     * 相似度检索：query -> embedding -> Milvus 近似检索 TopK。
     */
    public List<Document> search(String query, int topK) {
        return search(query, topK, 0);
    }

    /**
     * 相似度检索，可指定相似度阈值。
     *
     * @param similarityThreshold &lt;= 0 表示不过滤（全部返回）
     */
    public List<Document> search(String query, int topK, double similarityThreshold) {
        SearchRequest.Builder builder = SearchRequest.builder()
                .query(query)
                .topK(Math.max(1, topK));
        if (similarityThreshold > 0) {
            builder.similarityThreshold(similarityThreshold);
        }
        return vectorStore.similaritySearch(builder.build());
    }

    /**
     * 清空知识库：按 metadata 中的 collection 字段删除本集合的全部向量。
     *
     * <p>存在的意义：{@link #importDocument} 走的是 {@code vectorStore.add()}，
     * 每次调用都会生成新主键、<b>不做去重</b>。同一份文档导入两次，向量就会变成两份，
     * 检索时会返回重复片段、挤占 TopK 名额。重新导入前先调用本方法即可保证不累积。</p>
     *
     * <p>条数核对请直接查 Milvus：
     * {@code POST /v2/vectordb/entities/query} body
     * {@code {"collectionName":"jmu_handbook","filter":"","outputFields":["count(*)"]}}
     * —— Spring AI 的 VectorStore 接口不暴露计数能力，所以这里不做统计。</p>
     */
    public void clear() {
        vectorStore.delete(new FilterExpressionBuilder().eq("collection", collectionName).build());
        log.info("Cleared all vectors in collection {}", collectionName);
    }

    private String readText(String path) {
        try {
            return Files.readString(Path.of(path), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException("无法读取文档: " + path + "，" + e.getMessage(), e);
        }
    }

    private List<Document> buildChunks(String content, String sourcePath) {
        String[] sections = HEADING_SPLIT.split(content);
        List<Document> docs = new ArrayList<>();
        String currentHeading = null;

        for (String section : sections) {
            if (section.isBlank()) {
                continue;
            }
            String[] lines = section.split("\\R", 2);
            String first = lines[0].trim();
            if (first.startsWith("#")) {
                // 该小节带标题
                currentHeading = first.replaceFirst("^#{1,4}\\s*", "");
                section = lines.length > 1 ? lines[1] : "";
            }
            String heading = currentHeading == null ? "" : currentHeading;
            String body = section.trim();
            if (body.isEmpty()) {
                continue;
            }
            // 关键：把标题并入正文一起向量化。
            //
            // 原实现只把标题存进 metadata、正文里不带标题，导致「报到时间」这类
            // 只出现在标题中的词完全检索不到——对应片段正文只有
            // "2025年9月10日7:30-17:30"，里面根本没有"报到""时间"两个字。
            // 「学费」「接站」召不回同理。加上标题后，向量同时承载章节语义与正文语义。
            String text = heading.isEmpty() ? body : heading + "\n" + body;
            Map<String, Object> metadata = Map.of(
                    "source", sourcePath,
                    "heading", heading,
                    "collection", collectionName);
            docs.addAll(splitLongText(text, metadata));
        }
        return docs;
    }

    private List<Document> splitLongText(String text, Map<String, Object> metadata) {
        List<Document> out = new ArrayList<>();
        if (text.length() <= MAX_CHUNK_CHARS) {
            out.add(new Document(text, metadata));
            return out;
        }
        // 表格整段不切分（见 isMarkdownTable 注释）
        if (isMarkdownTable(text)) {
            log.debug("表格段落超过 {} 字但保持完整,不切分(共 {} 字)", MAX_CHUNK_CHARS, text.length());
            out.add(new Document(text, metadata));
            return out;
        }
        // 超长文本按段落("空行")切分，组装到不超过 MAX_CHUNK_CHARS 的块
        StringBuilder buf = new StringBuilder();
        for (String paragraph : text.split("(?m)^\\s*$")) {
            String p = paragraph.trim();
            if (p.isEmpty()) {
                continue;
            }
            if (buf.length() > 0 && buf.length() + p.length() + 1 > MAX_CHUNK_CHARS) {
                out.add(new Document(buf.toString().trim(), metadata));
                buf.setLength(0);
            }
            if (p.length() > MAX_CHUNK_CHARS) {
                // 极长段落直接硬切
                for (int i = 0; i < p.length(); i += MAX_CHUNK_CHARS) {
                    out.add(new Document(p.substring(i, Math.min(p.length(), i + MAX_CHUNK_CHARS)), metadata));
                }
                buf.setLength(0);
            } else {
                if (buf.length() > 0) {
                    buf.append('\n');
                }
                buf.append(p);
            }
        }
        if (!buf.isEmpty()) {
            out.add(new Document(buf.toString().trim(), metadata));
        }
        return out;
    }

    /**
     * 判断一个段落是否为 Markdown 表格。
     *
     * <p><b>为什么表格不能切：</b>表格一旦从中间被切断，表头和数据就分离了 ——
     * 检索命中的可能只是一堆没有列名的数字，模型根本看不懂哪一列是学费、哪一列是住宿费。
     * 所以表格整段作为一个块输出，<b>即使超过 {@link #MAX_CHUNK_CHARS} 也不切</b>。</p>
     *
     * <p>判定方式：连续出现至少 2 行以 {@code |} 开头（表头行 + 分隔行）。
     * 用「行首」判断是为了和校歌简谱区分 —— 简谱的 {@code |} 是小节线，在行中间。</p>
     */
    private boolean isMarkdownTable(String text) {
        int tableRows = 0;
        for (String line : text.split("\\R")) {
            if (TABLE_ROW.matcher(line).find()) {
                tableRows++;
                if (tableRows >= 2) {
                    return true;
                }
            }
        }
        return false;
    }
}
