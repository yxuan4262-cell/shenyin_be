package com.tongji.comment.service.impl;

import com.tongji.comment.api.dto.CommentItemResponse;
import com.tongji.comment.api.dto.CommentPageResponse;
import com.tongji.comment.api.dto.CommentSearchRequest;
import com.tongji.comment.mapper.CommentMapper;
import com.tongji.comment.model.Comment;
import com.tongji.comment.model.CommentRow;
import com.tongji.comment.service.CommentService;
import com.tongji.common.exception.BusinessException;
import com.tongji.common.exception.ErrorCode;
import com.tongji.knowpost.id.SnowflakeIdGenerator;
import com.tongji.knowpost.mapper.KnowPostMapper;
import com.tongji.knowpost.model.KnowPost;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;

@Service
@RequiredArgsConstructor
public class CommentServiceImpl implements CommentService {

    /** 知文已发布状态值，与 KnowPostServiceImpl 中的判定保持一致。 */
    private static final String POST_STATUS_PUBLISHED = "published";
    /** 评论正常状态值（软删除为 deleted）。 */
    private static final String COMMENT_STATUS_NORMAL = "normal";
    private static final String COMMENT_STATUS_DELETED = "deleted";

    /** 分页钳制常量：与 listByPost 共用，两个入口的规则必须一致。 */
    private static final int MAX_PAGE = 10_000;
    private static final int MAX_SIZE = 50;
    private static final int MAX_KEYWORD_LENGTH = 50;

    /** 允许的排序键。XML 里的 &lt;choose&gt; 与此一一对应。 */
    private static final Set<String> ALLOWED_SORT = Set.of("time_desc", "time_asc", "creator");
    private static final String DEFAULT_SORT = "time_desc";

    /** 允许的状态值。白名单而非黑名单：新增状态时默认不放行，比默认放行安全。 */
    private static final Set<String> ALLOWED_STATUS = Set.of(COMMENT_STATUS_NORMAL, COMMENT_STATUS_DELETED);

    /** LIKE 的转义符，与 XML 里的 ESCAPE '\\' 对应。 */
    private static final char LIKE_ESCAPE_CHAR = '\\';

    private final KnowPostMapper knowPostMapper;
    private final CommentMapper commentMapper;
    private final SnowflakeIdGenerator idGen;

    @Override
    @Transactional
    public long createComment(long userId, long postId, String content, Long parentId) {
        // ① 知文必须存在且已发布，否则视为不可评论
        KnowPost knowPost = knowPostMapper.findById(postId);
        if (knowPost == null || !POST_STATUS_PUBLISHED.equals(knowPost.getStatus())) {
            throw new BusinessException(ErrorCode.POST_NOT_FOUND);
        }

        // ② 楼中楼：父评论必须存在、未删除、且属于同一知文
        if (parentId != null) {
            Comment parent = commentMapper.findById(parentId);
            if (parent == null
                    || !COMMENT_STATUS_NORMAL.equals(parent.getStatus())
                    || !parent.getPostId().equals(postId)) {
                throw new BusinessException(ErrorCode.COMMENT_NOT_FOUND);
            }
        }

        // ③ 生成雪花 ID 并落库，返回新评论 ID
        long id = idGen.nextId();
        Instant now = Instant.now();
        Comment comment = Comment.builder()
                .id(id)
                .postId(postId)
                .creatorId(userId)
                .parentId(parentId)
                .content(content)
                .status(COMMENT_STATUS_NORMAL)
                .createTime(now)
                .updateTime(now)
                .build();
        commentMapper.insert(comment);
        return id;
    }

    @Override
    public CommentPageResponse listByPost(long postId, int page, int size, Long currentUserIdNullable) {
        int safePage = clampPage(page);
        int safeSize = clampSize(size);
        int offset = (safePage - 1) * safeSize;

        // 多查一条判断 hasMore：要 20 条就查 21 条，查到 21 条说明还有下一页
        List<CommentRow> rows = commentMapper.listByPost(postId, safeSize + 1, offset);
        return assemble(rows, safePage, safeSize);
    }

    @Override
    public void deleteComment(long userId, long commentId) {
        // ① 存在性：评论不存在或已软删除 → COMMENT_NOT_FOUND
        Comment comment = commentMapper.findById(commentId);
        if (comment == null || !COMMENT_STATUS_NORMAL.equals(comment.getStatus())) {
            throw new BusinessException(ErrorCode.COMMENT_NOT_FOUND);
        }
        // ② 归属权：存在但不是你的 → COMMENT_FORBIDDEN
        if (!comment.getCreatorId().equals(userId)) {
            throw new BusinessException(ErrorCode.COMMENT_FORBIDDEN);
        }
        // ③ 软删除：SQL 的 WHERE 仍带 creator_id，作为最后一道防线
        commentMapper.softDelete(commentId, userId);
    }

    // ==================================================================
    // 高级筛选
    // ==================================================================

    @Override
    public CommentPageResponse search(CommentSearchRequest request) {
        // ① 分页钳制：与 listByPost 完全同规则，两个入口行为一致
        int safePage = clampPage(request.page());
        int safeSize = clampSize(request.size());
        int offset = (safePage - 1) * safeSize;

        // ② keyword：null / 全空白 → null（让 XML 的 <if> 不成立），并转义 LIKE 通配符
        String keyword = normalizeKeyword(request.keyword());

        // ③ statusList：白名单过滤；null 或空列表 → 默认只查 normal
        //    这一步是安全边界：没有它，任何人传 statusList=deleted 就能看到已删除内容
        List<String> statusList = sanitizeStatusList(request.statusList());

        // ④ sort：白名单校验，非法值直接 400（而不是静默落 otherwise 让调用方误以为生效了）
        String sort = normalizeSort(request.sort());

        // ⑤ 时间：String → Instant，格式非法 400；再自检区间方向
        Instant startTime = parseInstant(request.startTime(), "startTime");
        Instant endTime = parseInstant(request.endTime(), "endTime");
        if (startTime != null && endTime != null && startTime.isAfter(endTime)) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "startTime 不能晚于 endTime");
        }

        List<CommentRow> rows = commentMapper.searchComments(
                request.postId(), request.creatorId(), keyword,
                startTime, endTime, statusList, sort,
                safeSize + 1, offset);

        return assemble(rows, safePage, safeSize);
    }

    // ==================================================================
    // 私有工具：钳制 / 归一化 / 组装
    // ==================================================================

    /**
     * 行模型 → 响应 DTO，并砍掉多查的那一条。
     * <p>
     * listByPost 与 search 共用：两个接口的出参结构完全一致，
     * 重复这段映射代码等于把"改一处忘一处"的 bug 提前埋好。
     */
    private CommentPageResponse assemble(List<CommentRow> rows, int safePage, int safeSize) {
        boolean hasMore = rows.size() > safeSize;
        List<CommentRow> page = hasMore ? rows.subList(0, safeSize) : rows;

        List<CommentItemResponse> items = new ArrayList<>(page.size());
        for (CommentRow row : page) {
            items.add(new CommentItemResponse(
                    // 雪花 ID 转字符串防前端 JS 精度丢失（Number 只有 53 位有效）
                    String.valueOf(row.getId()),
                    row.getContent(),
                    // parentId 可为 null（顶层评论）：先判空，避免 String.valueOf(null) 变成 "null" 字面量
                    row.getParentId() == null ? null : String.valueOf(row.getParentId()),
                    row.getAuthorNickname(),
                    row.getAuthorAvatar(),
                    0L,      // likeCount 占位：第七阶段接入计数系统后替换
                    false,   // liked 占位：同上，届时用 currentUserIdNullable 查用户维度状态
                    row.getCreateTime().toString(),
                    row.getStatus()
            ));
        }
        return new CommentPageResponse(items, safePage, safeSize, hasMore);
    }

    private static int clampPage(int page) {
        return Math.min(Math.max(page, 1), MAX_PAGE);
    }

    private static int clampSize(int size) {
        return Math.min(Math.max(size, 1), MAX_SIZE);
    }

    /**
     * 关键词归一化：去首尾空白，空串转 null，超长拒绝，并转义 LIKE 通配符。
     * <p>
     * <b>为什么必须转义</b>：用户搜 "%" 时，若不转义，
     * {@code LIKE '%%%'} 会匹配全表每一行——一次请求就能把数据库拖垮。
     * 转义后 "%" 变成字面量百分号，只匹配内容里真的含 "%" 的评论。
     * 配合 XML 里的 {@code ESCAPE '\\'} 生效。
     */
    private static String normalizeKeyword(String raw) {
        if (raw == null) {
            return null;
        }
        String trimmed = raw.trim();
        if (trimmed.isEmpty()) {
            // 空串必须转成 null：XML 里 <if test="keyword != null and keyword != ''"> 才不成立，
            // 否则会拼出 LIKE '%%'，等价于无条件匹配全表
            return null;
        }
        if (trimmed.length() > MAX_KEYWORD_LENGTH) {
            throw new BusinessException(ErrorCode.BAD_REQUEST,
                    "keyword 过长，最多 " + MAX_KEYWORD_LENGTH + " 字");
        }
        return escapeLikeWildcards(trimmed);
    }

    /** 转义 LIKE 的三个特殊字符：\ % _（反斜杠自身也要转义，否则 \% 会被误解）。 */
    private static String escapeLikeWildcards(String s) {
        StringBuilder sb = new StringBuilder(s.length() + 8);
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == LIKE_ESCAPE_CHAR || c == '%' || c == '_') {
                sb.append(LIKE_ESCAPE_CHAR);
            }
            sb.append(c);
        }
        return sb.toString();
    }

    /**
     * 状态白名单过滤。
     * <p>
     * 三种输入三种处理：
     * <ul>
     *   <li>null 或空列表 → 默认 {@code [normal]}。空列表若直接放行，
     *       XML 的 &lt;foreach&gt; 会渲染出 {@code status IN ()}，MySQL 语法错误。</li>
     *   <li>含非法值（如 "hacked"）→ 抛 400。<b>不静默丢弃</b>：
     *       静默丢弃会让调用方以为筛选生效了，实际返回的是别的东西，这种 bug 极难查。</li>
     *   <li>合法值 → 小写归一 + 去重后放行。</li>
     * </ul>
     */
    private static List<String> sanitizeStatusList(List<String> raw) {
        if (raw == null || raw.isEmpty()) {
            return List.of(COMMENT_STATUS_NORMAL);
        }
        List<String> kept = new ArrayList<>(raw.size());
        for (String item : raw) {
            if (item == null) {
                throw new BusinessException(ErrorCode.BAD_REQUEST, "statusList 不能含 null 元素");
            }
            String value = item.trim().toLowerCase(Locale.ROOT);
            if (!ALLOWED_STATUS.contains(value)) {
                throw new BusinessException(ErrorCode.BAD_REQUEST,
                        "statusList 含非法状态值：" + item + "，可选 normal / deleted");
            }
            if (!kept.contains(value)) {
                kept.add(value);
            }
        }
        return List.copyOf(kept);
    }

    /**
     * 排序键归一化。
     * <p>
     * 这里是 Service 的第一道白名单，XML 的 &lt;choose&gt; 是第二道。
     * 两道都要有：Service 这道负责"非法就报错"（可观测），
     * XML 那道负责"即使漏过也无法注入"（因为可能性被枚举死了，用户输入永远进不了 SQL 文本）。
     */
    private static String normalizeSort(String raw) {
        if (raw == null || raw.isBlank()) {
            return DEFAULT_SORT;
        }
        String value = raw.trim().toLowerCase(Locale.ROOT);
        // 容错别名：time 等同于 time_desc
        if ("time".equals(value)) {
            value = DEFAULT_SORT;
        }
        if (!ALLOWED_SORT.contains(value)) {
            throw new BusinessException(ErrorCode.BAD_REQUEST,
                    "sort 非法：" + raw + "，可选 time_desc / time_asc / creator");
        }
        return value;
    }

    /**
     * ISO-8601 字符串 → Instant。
     * <p>
     * 只接受带时区的瞬时时间格式（如 {@code 2026-09-01T00:00:00Z}）。
     * 不接受 {@code 2026-09-01} 这种纯日期——那需要指定时区才能变成瞬时点，
     * 让调用方明确写出来，比服务端偷偷假设一个时区要可靠。
     */
    private static Instant parseInstant(String raw, String fieldName) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            return Instant.parse(raw.trim());
        } catch (DateTimeParseException e) {
            throw new BusinessException(ErrorCode.BAD_REQUEST,
                    fieldName + " 格式非法：" + raw + "，需为 ISO-8601，例如 2026-09-01T00:00:00Z");
        }
    }
}
