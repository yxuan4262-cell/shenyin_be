package com.tongji.comment.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;

/**
 * 评论实体，与 comments 表一一对应。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class Comment {
    /** 雪花 ID，业务层生成 */
    private Long id;
    /** 所属知文 ID */
    private Long postId;
    /** 评论人用户 ID */
    private Long creatorId;
    /** 父评论 ID，顶层评论为 null（楼中楼） */
    private Long parentId;
    /** 评论内容，最多 500 字 */
    private String content;
    /** normal/deleted（软删除） */
    private String status;
    private Instant createTime;
    private Instant updateTime;
}
