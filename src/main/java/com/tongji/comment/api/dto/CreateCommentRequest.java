package com.tongji.comment.api.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * 创建评论请求。
 * <p>
 * postId/content 必填，parentId 选填（楼中楼回复时传父评论 ID）。
 */
public record CreateCommentRequest(
        @NotNull Long postId,
        @NotBlank @Size(max = 500) String content,
        Long parentId
) {}
