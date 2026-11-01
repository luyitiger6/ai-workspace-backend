package com.luyi.myfirst.web.attraction.dto;

import com.luyi.myfirst.common.web.ApiResponse;

import java.util.List;

/**
 * 景点列表 / 榜单的响应体（spec「列表的城市过滤、排序与条数上限」）。
 *
 * {@code total} 与 {@code items.size()} 不是一回事：完整列表时它是不受 {@code limit}
 * 约束的已发布总数，榜单时它是该榜的策展成员总数。前端因此能用
 * {@code total > items.length} 判断「还有没被截掉的条目」。
 *
 * 元素类型写 {@link AttractionListItemResponse} 而不是内联展开：10 号闸门要求
 * {@code List<X>} 里的 {@code X} 也是受管 record，这样两侧的字段比对才落得下去。
 */
public record AttractionListResponse(
        List<AttractionListItemResponse> items,
        long total) implements ApiResponse {
}