package com.tongji.comment.service;

import com.tongji.comment.api.dto.CommentPageResponse;
import com.tongji.comment.api.dto.CommentSearchRequest;

/**
 * 评论业务接口。
 * <p>
 * 分层纪律：本接口只出现业务概念（userId、postId、content），
 * 不出现任何 HTTP（@PathVariable）或校验注解（@NotNull）——
 * 参数合法性由 Controller 层的 DTO + @Valid 在边界拦截，
 * 操作者身份由 Controller 从 JWT 解析后以 userId 传入。
 */
public interface CommentService {

    /**
     * 创建评论。
     * <p>
     * 业务校验顺序：
     * ① 知文存在且已发布，否则抛 POST_NOT_FOUND；
     * ② parentId 非空时：父评论存在、未删除、且属于同一知文，否则抛 COMMENT_NOT_FOUND；
     * ③ 通过后生成雪花 ID 并落库。
     *
     * @param userId   评论人（操作者身份，必填）
     * @param postId   所属知文
     * @param content  评论内容（已在边界校验过非空与长度）
     * @param parentId 父评论 ID，顶层评论传 null
     * @return 新评论的雪花 ID
     */
    long createComment(long userId, long postId, String content, Long parentId);

    /**
     * 某知文下的评论分页（公开接口，未登录也可看）。
     *
     * @param postId                知文 ID
     * @param page                  页码（≥1，越界由实现层钳制）
     * @param size                  每页条数（实现层钳制到 [1,50]）
     * @param currentUserIdNullable 当前用户，null 表示未登录；仅用于填充 liked 个性化字段
     * @return 分页响应（items + page + size + hasMore）
     */
    CommentPageResponse listByPost(long postId, int page, int size, Long currentUserIdNullable);

    /**
     * 删除自己的评论（软删除）。
     * <p>
     * commentId 为全局唯一的雪花 ID，无需 postId 辅助定位；
     * 归属校验通过 creatorId 判断。
     * 评论不存在/已删除抛 COMMENT_NOT_FOUND，非本人抛 COMMENT_FORBIDDEN。
     *
     * @param userId    操作者（必须是评论作者本人）
     * @param commentId 评论 ID
     */
    void deleteComment(long userId, long commentId);

    /**
     * 评论高级筛选（需登录）。
     * <p>
     * <b>为什么参数是一个查询对象而不是 9 个位置参数</b>：
     * 9 个同类型参数在调用点无法靠位置分辨（{@code search(null, null, null, ...)}），
     * 极易传错顺序且编译器不报错。查询对象让每个值都带名字。
     * 这不算破坏分层——{@link CommentSearchRequest} 是纯数据载体，
     * 不含 @RequestParam / @Valid 等 HTTP 语义，符合本接口的纪律。
     * <p>
     * 实现层的四道防线（详见 CommentServiceImpl）：
     * ① 分页钳制；② keyword 归一化 + LIKE 通配符转义；
     * ③ statusList 白名单过滤（默认只查 normal，杜绝越权看到已删除内容）；
     * ④ sort 白名单校验 + 时间区间自检。
     *
     * @param request 筛选条件，所有字段皆可选
     * @return 分页响应，items 中带 status 字段以区分 normal / deleted
     */
    CommentPageResponse search(CommentSearchRequest request);

}
