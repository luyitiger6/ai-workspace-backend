package com.luyi.myfirst.service.travel.model;

import java.math.BigDecimal;

/**
 * 单个币种相对基准币的汇率。
 *
 * 用 record 列表而不是 {@code Map<String, BigDecimal>}：契约闸门对 Map 类型 fail-closed，
 * 而列表让「币种代码」与「数值」各自成为受管字段，改字段名会被闸门拦下（design D4）。
 *
 * {@code value} 用 {@code BigDecimal} 而不是 {@code double}：汇率是十进制小数，
 * double 在序列化时可能吐出科学计数法，而 spec 明确禁止界面出现科学计数法。
 */
public record CurrencyQuote(String code, BigDecimal value) {
}
