package com.tongji.comment.api.dto;

import java.util.List;

/**
 * 评论分页响应。
 * <p>
 * 结构对齐 {@code FeedPageResponse}：items + 分页三件套。
 */
public record CommentPageResponse(
        List<CommentItemResponse> items,
        int page,
        int size,
        boolean hasMore
) {}
