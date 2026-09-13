package com.wuyunbin.rag.service;

import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.messages.Message;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * 会话生命周期管理：清空记忆、查看窗口内消息。
 *
 * <p>与 {@link ChatService} 分开：前者负责"说什么"，这里负责"会话本身"。</p>
 */
@Service
@RequiredArgsConstructor
public class ConversationService {

    private static final Logger log = LoggerFactory.getLogger(ConversationService.class);

    private final ChatMemory chatMemory;

    /**
     * 清空指定会话的记忆（用于开启新话题）。会话 ID 本身仍可继续使用。
     */
    public void clear(String conversationId) {
        List<Message> before = chatMemory.get(conversationId);
        chatMemory.clear(conversationId);
        log.info("清空会话记忆: cid={} 原有消息={} 条", conversationId, before.size());
    }

    /**
     * 查看指定会话当前窗口内的消息列表。
     */
    public List<Message> history(String conversationId) {
        List<Message> messages = chatMemory.get(conversationId);
        log.debug("查看会话记忆: cid={} 消息={} 条", conversationId, messages.size());
        return messages;
    }
}
