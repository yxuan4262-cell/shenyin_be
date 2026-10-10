package com.tongji.comment.mapper;

import com.tongji.comment.model.Comment;
import com.tongji.comment.model.CommentRow;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.time.Instant;
import java.util.List;

/**
 * 评论数据访问接口，SQL 定义见 resources/mapper/CommentMapper.xml。
 * <p>
 * <b>@Param 的必要性</b>：MyBatis 默认只能拿到 arg0/arg1/param1/param2 这种无意义名字。
 * 加了 {@code @Param("keyword")} 之后，XML 里才能写 {@code #{keyword}}、
 * {@code <if test="keyword != null">}。多参数方法漏写 @Param 是运行期报错，
 * 编译期完全查不出来——这是 MyBatis 最典型的"编译过了但跑不通"。
 */
@Mapper
public interface CommentMapper {

    /** 插入新评论（ID 由业务层雪花算法生成）。 */
    void insert(Comment comment);

    /** 按 ID 查询单条评论（含已删除，状态由调用方判断）。 */
    Comment findById(@Param("id") Long id);

    /**
     * 软删除：status 置为 deleted。
     * WHERE 同时限定 creator_id，归属权校验压进 SQL 层；
     * 返回影响行数，0 表示评论不存在或不属于该用户。
     */
    int softDelete(@Param("id") Long id, @Param("creatorId") Long creatorId);

    /**
     * 某知文下的评论分页（按时间倒序，仅 normal 状态）。
     * 走索引 ix_comments_post_status_ct (post_id, status, create_time)。
     * 调用方按 limit+1 传入以判断 hasMore。
     */
    List<CommentRow> listByPost(@Param("postId") Long postId,
                                @Param("limit") int limit,
                                @Param("offset") int offset);

    /**
     * 高级筛选：所有条件皆可选，全为 null 时等价于"按状态查全部评论"。
     * <p>
     * <b>返回 List 而非单个对象</b>：这是分页查询，命中 0..N 行。
     * 返回类型写成 {@code Comment} 会在命中多行时抛 TooManyResultsException。
     * <p>
     * <b>返回 CommentRow 而非 Comment</b>：JOIN users 带出了昵称/头像，
     * 实体 Comment 装不下这两个字段。查询专用载体与实体分离，是数据层的常规做法。
     * <p>
     * <b>时间参数用 Instant 而非 String</b>：MyBatis 内置 InstantTypeHandler，
     * 会以 setTimestamp(Timestamp.from(instant)) 绑定，类型安全；
     * 传 String 则依赖 MySQL 的隐式类型转换，格式稍有偏差就静默查不到数据。
     * 字符串 → Instant 的转换与校验在 Service 层完成。
     *
     * @param postId     限定知文，null 不限
     * @param creatorId  限定作者，null 不限
     * @param keyword    内容模糊匹配（已由 Service 转义 LIKE 通配符），null 不限
     * @param startTime  create_time 下界（含），null 不限
     * @param endTime    create_time 上界（含），null 不限
     * @param statusList 状态白名单（已由 Service 校验，保证非空且值合法）
     * @param sort       排序键，XML 用 &lt;choose&gt; 枚举匹配，非法值落 otherwise
     * @param limit      每页条数（调用方按 size+1 传入以判断 hasMore）
     * @param offset     偏移量
     */
    List<CommentRow> searchComments(@Param("postId") Long postId,
                                    @Param("creatorId") Long creatorId,
                                    @Param("keyword") String keyword,
                                    @Param("startTime") Instant startTime,
                                    @Param("endTime") Instant endTime,
                                    @Param("statusList") List<String> statusList,
                                    @Param("sort") String sort,
                                    @Param("limit") int limit,
                                    @Param("offset") int offset);
}
