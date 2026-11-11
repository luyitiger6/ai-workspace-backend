package com.luyi.myfirst.web.travel.dto;

import com.luyi.myfirst.common.web.ApiResponse;

import java.time.Instant;
import java.util.List;

/**
 * 汇率端点的响应体（spec「汇率查询的币种范围」）。
 *
 * {@code base} 恒为 {@code CNY}，仍显式返回：前端把它写在「1 CNY =」这一行上，
 * 硬编码在页面里会让「基准币变了」变成一处静默错位的文案。
 *
 * {@code updatedAt} 用 {@code Instant}（→ ISO 字符串），是数据源声明的最后更新时刻，
 * 不是我们拉取的时刻——数据源每天只更新一次，这是 spec 明令不许制造「实时」错觉的地方。
 *
 * {@code rates} 只含 spec 固定的五个币种，顺序即展示顺序。
 */
public record CurrencyResponse(
        String base,
        Instant updatedAt,
        List<CurrencyRateResponse> rates
) implements ApiResponse {
}
