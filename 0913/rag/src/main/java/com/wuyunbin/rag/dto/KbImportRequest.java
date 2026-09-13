package com.wuyunbin.rag.dto;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * 知识库文档导入请求。
 */
@Schema(description = "知识库文档导入请求")
public record KbImportRequest(

        @Schema(description = "待导入文档的绝对路径；为空则使用服务端默认文档（jmu 新生手册）")
        String path
) {
}
