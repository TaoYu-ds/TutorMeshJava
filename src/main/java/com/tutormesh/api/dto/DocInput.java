package com.tutormesh.api.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;

@Schema(description = "知识库文档")
public record DocInput(
        @Schema(description = "文档标题", example = "导数补充讲义")
        @NotBlank String title,
        @Schema(description = "文档内容", example = "判断单调性：先求导，再求导数为零的分界点，最后按区间判断符号。")
        @NotBlank String content,
        @jakarta.validation.constraints.Size(min = 1, max = 256) String documentId
) {
}
