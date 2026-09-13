package com.wuyunbin.rag.dto;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * 聊天请求体。
 *
 * <p>conversationId 为空时视为一次性单轮对话：服务端生成新会话 ID 并在响应中返回，
 * 该次请求不携带任何历史上下文。</p>
 */
@Schema(description = "聊天请求")
public record ChatRequest(

        @Schema(description = "会话 ID。首轮不传，服务端生成并在响应中返回；后续轮次回传同一 ID 以延续上下文",
                example = "3f2a9c1e-8b7d-4a2f-9c11-0d5e6f7a8b9c")
        String conversationId,

        @Schema(description = "用户输入的消息内容", example = "你好，请介绍一下你自己。", requiredMode = Schema.RequiredMode.REQUIRED)
        String message
) {
}
