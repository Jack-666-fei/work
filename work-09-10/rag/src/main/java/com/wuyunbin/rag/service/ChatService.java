package com.wuyunbin.rag.service;

import com.wuyunbin.rag.dto.ChatResponse;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.stereotype.Service;

import java.util.UUID;

/**
 * 聊天服务：支持多轮上下文。
 *
 * <p>上下文由 {@link ChatMemory} 承载，通过 advisor 参数 {@link ChatMemory#CONVERSATION_ID}
 * 关联到具体会话。</p>
 */
@Service
@RequiredArgsConstructor
public class ChatService {

    private static final Logger log = LoggerFactory.getLogger(ChatService.class);

    /** 日志里问题/回复的预览长度，完整内容见召回日志与 /api/chat/{id}/messages */
    private static final int PREVIEW_CHARS = 100;

    private final ChatClient chatClient;

    /**
     * 多轮对话：同一 conversationId 的多次调用共享上下文。
     *
     * @param conversationId 会话 ID；为空或空白时生成新会话（等价于无上下文的一次性单轮对话）
     * @param message        用户消息
     * @return 模型回复 + 本次实际使用的会话 ID
     */
    public ChatResponse chat(String conversationId, String message) {
        boolean newConversation = (conversationId == null || conversationId.isBlank());
        String cid = newConversation ? UUID.randomUUID().toString() : conversationId;
        int questionLength = message == null ? 0 : message.length();

        log.info("对话请求: cid={} 类型={} 问题={}字 预览=\"{}\"",
                cid, newConversation ? "新会话" : "续接", questionLength, abbreviate(message));

        long start = System.currentTimeMillis();
        try {
            String reply = chatClient.prompt()
                    .user(message)
                    // 必须显式传入会话 ID：BaseChatMemoryAdvisor#getConversationId 对缺失值
                    // 会直接抛 IllegalArgumentException，不会退化成默认会话。
                    .advisors(a -> a.param(ChatMemory.CONVERSATION_ID, cid))
                    .call()
                    .content();

            long elapsed = System.currentTimeMillis() - start;
            int replyLength = reply == null ? 0 : reply.length();

            log.info("对话完成: cid={} 耗时={}ms 回复={}字 预览=\"{}\"",
                    cid, elapsed, replyLength, abbreviate(reply));

            if (replyLength == 0) {
                log.warn("对话完成但回复为空: cid={} 耗时={}ms —— 请检查模型是否只输出了思维内容，或 max_tokens 过小", cid, elapsed);
            }

            return new ChatResponse(cid, reply);

        } catch (Exception e) {
            long elapsed = System.currentTimeMillis() - start;
            log.error("对话失败: cid={} 耗时={}ms 问题={}字", cid, elapsed, questionLength, e);
            throw e;
        }
    }

    private String abbreviate(String text) {
        if (text == null) {
            return "";
        }
        String flat = text.replaceAll("\\R", " ");
        return flat.length() <= PREVIEW_CHARS ? flat : flat.substring(0, PREVIEW_CHARS) + "…";
    }
}
