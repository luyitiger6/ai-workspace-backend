package com.luyi.myfirst.service.attraction.model;

import java.util.List;

/**
 * 列表 / 榜单的一页（spec「列表的城市过滤、排序与条数上限」）。
 *
 * {@code total} 的口径随路径不同而不同，这是刻意的：
 *
 * - 完整列表：{@code total} = 满足城市过滤的已发布景点总数，与 {@code limit} 无关，
 *   由 count 查询给出；
 * - 榜单：{@code total} = 该榜的策展成员总数，由服务层截断前先量出来的长度给出。
 *
 * 两条路都不拿 {@code items.size()} 充总数——那会让「还有多少条」在前端永远等于
 * 「这一页有几条」，翻页判断随之失效。
 */
public record AttractionPage(List<AttractionSummary> items, long total) {
}