package com.wuyunbin.rag.dto;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * 知识库向量检索请求。
 */
@Schema(description = "知识库向量检索请求")
public record KbSearchRequest(

        @Schema(description = "查询内容", example = "2025级新生什么时候报到？", requiredMode = Schema.RequiredMode.REQUIRED)
        String query,

        @Schema(description = "返回条数，默认 5")
        Integer topK
) {
}
