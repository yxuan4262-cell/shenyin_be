package com.tongji.comment.model;

import lombok.Data;

import java.time.Instant;

/**
 * 评论列表查询行：comments JOIN users 的结果载体。
 * <p>
 * 与实体 {@link Comment} 的区别：多了作者展示字段（昵称/头像），
 * 专供分页列表接口使用，避免查回整行 users 数据。
 */
@Data
public class CommentRow {
    private Long id;
    private Long postId;
    private Long creatorId;
    private Long parentId;
    private String content;
    /** normal / deleted。普通列表恒为 normal；高级筛选可能查出 deleted，靠它区分。 */
    private String status;
    private String authorNickname;
    private String authorAvatar;
    private Instant createTime;
}
