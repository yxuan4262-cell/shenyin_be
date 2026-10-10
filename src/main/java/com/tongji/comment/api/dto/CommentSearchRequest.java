package com.tongji.comment.api.dto;

import java.util.List;

/**
 * 评论高级筛选请求（查询对象）。
 * <p>
 * <b>为什么全用包装类型</b>：所有字段皆可选，全为 null 时等价于"查全部评论"。
 * 用 {@code Long} 而非 {@code long}，才能区分"没传"与"传了 0"——
 * 基本类型拿到 null 会 NPE，拿到缺省会变 0，两者都无法表达"不限制"。
 * <p>
 * <b>为什么 startTime/endTime 是 String 而不是 Instant</b>：
 * 本接口用 GET，参数来自 URL 查询串，由 Spring 的 ConversionService 转换，
 * 而它默认<b>没有</b> String → Instant 的转换器（Jackson 的 JavaTimeModule 只管 JSON 请求体）。
 * 所以传输层收 String，由 Service 显式 {@code Instant.parse} 并在格式非法时抛 400。
 * 这是一层"防腐"：外部格式的脏活挡在 Service，Mapper 只接受强类型 Instant。
 * <p>
 * <b>为什么 page/size 用 int</b>：Controller 的 {@code @RequestParam(defaultValue=...)}
 * 保证了它们永远非 null，无需包装类型；钳制逻辑仍在 Service。
 * <p>
 * 本 DTO 不加校验注解：筛选条件没有"必填"一说，合法性由 Service 的钳制与白名单保证。
 */
public record CommentSearchRequest(
        Long postId,             // 限定某篇知文，null 表示不限
        Long creatorId,          // 限定某个作者，null 表示不限
        String keyword,          // 内容模糊匹配关键词，null 或空白表示不限
        String startTime,        // create_time 下界（含），ISO-8601，null 表示不限
        String endTime,          // create_time 上界（含），ISO-8601，null 表示不限
        List<String> statusList, // 状态白名单，null 或空列表 → 默认只查 normal
        String sort,             // 排序：time_desc（默认）/ time_asc / creator
        int page,                // 页码，从 1 开始，Service 钳制到 [1,10000]
        int size                 // 每页条数，Service 钳制到 [1,50]
) {
}
