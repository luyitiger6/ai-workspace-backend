package com.luyi.myfirst.web.travel.dto;

import java.math.BigDecimal;

/**
 * 汇率表里的一行：币种代码与「1 CNY 兑该币种」的数值。
 *
 * 独立成 record 而不是让 {@code CurrencyResponse} 用 {@code Map<String, BigDecimal>}：
 * 契约闸门的类型映射表对 Map fail-closed，那样整个响应体就不受管治（design D4）。
 *
 * {@code value} 用 {@code BigDecimal}（→ JSON number，不出现科学计数法）。
 */
public record CurrencyRateResponse(String code, BigDecimal value) {
}
