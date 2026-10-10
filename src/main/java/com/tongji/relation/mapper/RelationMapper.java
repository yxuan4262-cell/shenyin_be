package com.tongji.relation.mapper;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.MapKey;

import java.util.List;
import java.util.Map;

/**
 * 关系表数据访问层。
 * 职责：维护关注/粉丝关系的插入与逻辑取消，分页读取与行数据回填，统计有效关系计数。
 */
@Mapper
public interface RelationMapper {
    /**
     * 插入关注关系。
     * @param id 主键ID
     * @param fromUserId 发起关注的用户ID
     * @param toUserId 被关注的用户ID
     * @param relStatus 关系状态
     * @return 影响行数
     */
    int insertFollowing(@Param("id") Long id,
                        @Param("fromUserId") Long fromUserId,
                        @Param("toUserId") Long toUserId,
                        @Param("relStatus") Integer relStatus);

    /**
     * 取消关注关系（逻辑更新）。
     * @param fromUserId 发起者
     * @param toUserId 目标者
     * @return 影响行数
     */
    int cancelFollowing(@Param("fromUserId") Long fromUserId,
                        @Param("toUserId") Long toUserId);

    /**
     * 插入粉丝关系。
     * @param id 主键ID
     * @param toUserId 被关注者
     * @param fromUserId 关注者
     * @param relStatus 关系状态
     * @return 影响行数
     */
    int insertFollower(@Param("id") Long id,
                        @Param("toUserId") Long toUserId,
                        @Param("fromUserId") Long fromUserId,
                        @Param("relStatus") Integer relStatus);

    /**
     * 取消粉丝关系（逻辑更新）。
     * @param toUserId 被关注者
     * @param fromUserId 关注者
     * @return 影响行数
     */
    int cancelFollower(@Param("toUserId") Long toUserId,
                       @Param("fromUserId") Long fromUserId);

    /**
     * 判断是否存在关注关系。
     * @param fromUserId 发起者
     * @param toUserId 目标者
     * @return 是否存在（>0 表示存在）
     */
    int existsFollowing(@Param("fromUserId") Long fromUserId,
                        @Param("toUserId") Long toUserId);

    /**
     * 列出关注用户ID（偏移分页）。
     * @param fromUserId 发起者
     * @param limit 上限
     * @param offset 偏移
     * @return 关注用户ID列表
     */
    List<Long> listFollowing(@Param("fromUserId") Long fromUserId,
                                       @Param("limit") int limit,
                                       @Param("offset") int offset);

    /**
     * 列出粉丝用户ID（偏移分页）。
     * @param toUserId 被关注者
     * @param limit 上限
     * @param offset 偏移
     * @return 粉丝用户ID列表
     */
    List<Long> listFollowers(@Param("toUserId") Long toUserId,
                                       @Param("limit") int limit,
                                       @Param("offset") int offset);

    /**
     * 列出关注行用于缓存回填（包含 createdAt）。
     * @param fromUserId 发起者
     * @param limit 上限
     * @param offset 偏移
     * @return 以 toUserId 作为键的行映射
     */
    @MapKey("toUserId")
    Map<Long, Map<String, Object>> listFollowingRows(@Param("fromUserId") Long fromUserId,
                                                     @Param("limit") int limit,
                                                     @Param("offset") int offset);

    /**
     * 列出粉丝行用于缓存回填（包含 createdAt）。
     * @param toUserId 被关注者
     * @param limit 上限
     * @param offset 偏移
     * @return 以 fromUserId 作为键的行映射
     */
    @MapKey("fromUserId")
    Map<Long, Map<String, Object>> listFollowerRows(@Param("toUserId") Long toUserId,
                                                    @Param("limit") int limit,
                                                    @Param("offset") int offset);

    /**
     * 统计关注数（有效关系）。
     */
    int countFollowingActive(@Param("fromUserId") Long fromUserId);

    /**
     * 统计粉丝数（有效关系）。
     */
    int countFollowerActive(@Param("toUserId") Long toUserId);

    /**
     * 批量判定关注关系：在候选集合中筛出 fromUserId 已有效关注的用户ID。
     *
     * <p>用途是消除列表渲染的 N+1——一页 20 条知文需要 20 个「我关注了这个作者吗」的答案，
     * 逐个调用 {@link #existsFollowing} 是 20 次 SQL，本方法 1 次。</p>
     *
     * <p><b>契约警告</b>：返回行数 ≤ 入参个数。未关注的、已取关的（rel_status=0）、
     * 以及 users 表里根本不存在的 id，都不会出现在结果里，而且结果顺序不保证与入参一致。
     * 因此调用方【绝对不能】按下标 zip，必须先把结果装进 Set，再遍历【输入】逐个判定。</p>
     *
     * @param fromUserId 发起关注的用户ID
     * @param candidateIds 候选用户ID列表；调用方须保证非空且已去重，否则 foreach 会拼出非法的 IN ()
     * @return 候选中已被 fromUserId 有效关注的用户ID子集
     */
    List<Long> listFollowingAmong(@Param("fromUserId") Long fromUserId,
                                  @Param("candidateIds") List<Long> candidateIds);
}

