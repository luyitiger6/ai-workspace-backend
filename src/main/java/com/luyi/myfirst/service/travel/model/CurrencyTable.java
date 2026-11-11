package com.luyi.myfirst.service.travel.model;

import java.time.Instant;
import java.util.List;

/**
 * 以单一基准币为中心的一张汇率表（spec「汇率查询的币种范围」）。
 *
 * {@code updatedAt} 是数据源声明的最后更新时刻，不是我们拉取的时刻。数据源每天只更新一次，
 * 用拉取时刻标注新鲜度会把「昨天的汇率」说成「刚刚取到的汇率」，正是 spec 禁止的错觉。
 *
 * {@code rates} 只含 spec 固定下来的五个币种，不是数据源返回的全部——数据源给了上百个，
 * 全量透传会让前端不得不自己再筛一遍，等于把「展示哪几个」的决策散到两处。
 */
public record CurrencyTable(
        String base,
        Instant updatedAt,
        List<CurrencyQuote> rates
) {
}
