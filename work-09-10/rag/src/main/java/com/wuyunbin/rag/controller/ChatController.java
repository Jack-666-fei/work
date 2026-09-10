package com.wuyunbin.rag.controller;

import com.wuyunbin.rag.dto.ChatRequest;
import com.wuyunbin.rag.dto.ChatResponse;
import com.wuyunbin.rag.service.ChatService;
import com.wuyunbin.rag.service.ConversationService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.ai.chat.messages.Message;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 聊天接口（多轮对话）。
 */
@RestController
@RequestMapping("/api/chat")
@RequiredArgsConstructor
@Tag(name = "聊天接口", description = "多轮对话接口：通过 conversationId 延续上下文")
public class ChatController {

    private final ChatService chatService;
    private final ConversationService conversationService;

    @PostMapping
    @Operation(summary = "多轮聊天",
            description = "首轮不传 conversationId，服务端生成并在响应中返回；后续轮次回传同一 ID 即可延续上下文")
    public ChatResponse chat(@RequestBody ChatRequest request) {
        return chatService.chat(request.conversationId(), request.message());
    }

    @DeleteMapping("/{conversationId}")
    @Operation(summary = "清空会话记忆", description = "丢弃该会话的全部上下文，会话 ID 本身仍可继续使用")
    public Map<String, Object> clear(@PathVariable("conversationId") String conversationId) {
        conversationService.clear(conversationId);
        Map<String, Object> resp = new LinkedHashMap<>();
        resp.put("conversationId", conversationId);
        resp.put("cleared", true);
        return resp;
    }

    @GetMapping("/{conversationId}/messages")
    @Operation(summary = "查看会话记忆", description = "返回该会话当前窗口内的消息列表，用于验证上下文是否生效及窗口截断行为")
    public Map<String, Object> history(@PathVariable("conversationId") String conversationId) {
        List<Message> messages = conversationService.history(conversationId);
        List<Map<String, Object>> items = messages.stream().map(message -> {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("type", message.getMessageType().name());
            item.put("text", message.getText());
            return item;
        }).toList();
        Map<String, Object> resp = new LinkedHashMap<>();
        resp.put("conversationId", conversationId);
        resp.put("count", items.size());
        resp.put("messages", items);
        return resp;
    }
}
