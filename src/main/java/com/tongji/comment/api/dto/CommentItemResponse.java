package com.tongji.comment.api.dto;

/**
 * 评论列表单条记录。
 * <p>
 * 风格对齐 {@code FeedItemResponse}：ID 用字符串防精度丢失，
 * 只暴露展示所需字段，不带数据库内部字段。
 */
public record CommentItemResponse(
        String commentId,
        String content,
        String parentId,        // 楼中楼：回复的父评论 ID，顶层评论为 null
        String authorNickname,
        String authorAvatar,
        Long likeCount,
        Boolean liked,          // 当前用户是否已赞；未登录请求固定 false
        String createdAt,       // ISO-8601 时间字符串
        String status           // normal / deleted：仅高级筛选接口可能返回 deleted
) {}
