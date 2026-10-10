package com.tongji.comment.api;

import com.tongji.auth.token.JwtService;
import com.tongji.comment.api.dto.CommentPageResponse;
import com.tongji.comment.api.dto.CommentSearchRequest;
import com.tongji.comment.api.dto.CreateCommentRequest;
import com.tongji.comment.api.dto.CreateCommentResponse;
import com.tongji.comment.service.CommentService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 评论接口。
 * <p>
 * 分层纪律：本类只做三件事——收参数、解析身份、调 Service。
 * 不写任何业务判断（知文是否存在、评论归谁），那些是 Service 的职责。
 */
@RestController
@RequestMapping("/api/v1/comments")
@Validated
@RequiredArgsConstructor
public class CommentController {

    private final CommentService commentService;
    private final JwtService jwtService;

    /**
     * 发表评论。parentId 为空是顶层评论，非空是楼中楼回复。
     * <p>
     * 校验发生在进入方法体之前：@Valid 触发 CreateCommentRequest 上的
     * @NotNull/@NotBlank/@Size，不合格直接 400，Service 收到的必定是干净数据。
     */
    @PostMapping
    public CreateCommentResponse create(@Valid @RequestBody CreateCommentRequest request,
                                        @AuthenticationPrincipal Jwt jwt) {
        long userId = jwtService.extractUserId(jwt);
        long commentId = commentService.createComment(
                userId, request.postId(), request.content(), request.parentId());
        return new CreateCommentResponse(String.valueOf(commentId));
    }

    /**
     * 某知文下的评论分页（按时间倒序）。
     * <p>
     * 允许未登录访问（与知文详情页一致），故 jwt 可能为 null，需判空。
     * page/size 不在此处校验，由 Service 内部钳制——读接口宽容。
     */
    @GetMapping("/post/{postId}")
    public CommentPageResponse listByPost(@PathVariable("postId") long postId,
                                          @RequestParam(value = "page", defaultValue = "1") int page,
                                          @RequestParam(value = "size", defaultValue = "20") int size,
                                          @AuthenticationPrincipal Jwt jwt) {
        Long userId = (jwt == null) ? null : jwtService.extractUserId(jwt);
        return commentService.listByPost(postId, page, size, userId);
    }

    /**
     * 删除自己的评论（软删除）。成功返回 204，无响应体。
     * <p>
     * 不需要 postId：commentId 是全局唯一雪花 ID，自带定位能力；
     * 归属权由 Service 比对 creatorId 判断。
     */
    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@PathVariable("id") long id,
                                       @AuthenticationPrincipal Jwt jwt) {
        long userId = jwtService.extractUserId(jwt);
        commentService.deleteComment(userId, id);
        return ResponseEntity.noContent().build();
    }

    /**
     * 评论高级筛选。<b>需登录</b>——未在 SecurityConfig 的 permitAll 名单里，
     * 因为它能按 creatorId 跨知文检索、还能查 deleted 评论，属管理向能力。
     * <p>
     * <b>为什么是 GET 而不是 POST</b>：这是一次只读查询，GET 语义上安全且可缓存，
     * 也能直接在浏览器地址栏试。POST 用于搜索会破坏 HTTP 的幂等/缓存约定。
     * <p>
     * <b>为什么用一堆 @RequestParam 而不是 @ModelAttribute 直接绑到 record</b>：
     * 显式声明每个参数的 required/defaultValue，读代码的人一眼看全接口契约；
     * 而且避开 Spring 对 record 构造器绑定 List 与自定义类型时的行为差异。
     * 代价是方法签名长——这里参数不到 10 个，可读性收益大于长度成本。
     * <p>
     * <b>路径冲突提醒</b>：本类已有一个 {@code @PostMapping}（无子路径），
     * 若这里也写成 {@code @PostMapping("")}，两个方法会映射到同一个
     * {@code POST /api/v1/comments}，启动时直接抛
     * {@code Ambiguous mapping} 让应用起不来。
     */
    @GetMapping("/search")
    public CommentPageResponse search(
            @RequestParam(value = "postId", required = false) Long postId,
            @RequestParam(value = "creatorId", required = false) Long creatorId,
            @RequestParam(value = "keyword", required = false) String keyword,
            @RequestParam(value = "startTime", required = false) String startTime,
            @RequestParam(value = "endTime", required = false) String endTime,
            @RequestParam(value = "statusList", required = false) List<String> statusList,
            @RequestParam(value = "sort", required = false) String sort,
            @RequestParam(value = "page", defaultValue = "1") int page,
            @RequestParam(value = "size", defaultValue = "20") int size) {
        CommentSearchRequest request = new CommentSearchRequest(
                postId, creatorId, keyword, startTime, endTime, statusList, sort, page, size);
        return commentService.search(request);
    }
}
