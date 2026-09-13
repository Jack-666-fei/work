package com.wuyunbin.rag.dto;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * 聊天响应体。
 */
@Schema(description = "聊天响应")
public record ChatResponse(

        @Schema(description = "本次对话的会话 ID，后续轮次需回传该值以延续上下文")
        String conversationId,

        @Schema(description = "模型回复内容")
        String reply
) {
}
