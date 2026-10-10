package com.tongji.counter.service;

import java.util.List;
import java.util.Map;

public interface CounterService {
    /**
     * 点赞：仅在之前未点赞时置位并 +1。
     * @return 是否发生状态变化（true 表示这次操作生效）
     */
    boolean like(String entityType, String entityId, long userId);

    /**
     * 取消点赞：仅在之前已点赞时清位并 -1。
     * @return 是否发生状态变化（true 表示这次操作生效）
     */
    boolean unlike(String entityType, String entityId, long userId);

    /**
     * 收藏：仅在之前未收藏时置位并 +1。
     */
    boolean fav(String entityType, String entityId, long userId);

    /**
     * 取消收藏：仅在之前已收藏时清位并 -1。
     */
    boolean unfav(String entityType, String entityId, long userId);

    /**
     * 获取指定指标的计数。
     */
    Map<String, Long> getCounts(String entityType, String entityId, List<String> metrics);

    Map<String, Map<String, Long>> getCountsBatch(String entityType, List<String> entityIds, List<String> metrics);

    /**
     * 判断是否点赞/收藏（位图）。
     */
    boolean isLiked(String entityType, String entityId, long userId);
    boolean isFaved(String entityType, String entityId, long userId);

    /**
     * 批量判断当前用户对多个实体是否点赞：一次 pipeline 打包所有 GETBIT，消除逐条查询的 N+1。
     * @return entityId -> 是否点赞
     */
    Map<String, Boolean> isLikedBatch(String entityType, List<String> entityIds, long userId);

    /**
     * 批量判断当前用户对多个实体是否收藏。
     * @return entityId -> 是否收藏
     */
    Map<String, Boolean> isFavedBatch(String entityType, List<String> entityIds, long userId);
}
