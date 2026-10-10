package com.tongji.comment.api.dto;

/**
 * 创建评论响应：返回新评论 ID（字符串避免前端精度丢失）。
 */
public record CreateCommentResponse(
        String commentId) {}
