package com.luyi.myfirst.dao.attraction.entity;

/**
 * 景点详情页专属的英文文案块（spec「详情返回完整决策字段」）。
 *
 * 十个字段全是可空字符串，语义一致（「内容侧还没写」），因此合成一个记录整体写入，
 * 而不是在实体上开十个单字段写路径——后者会让「只写了门票、忘了地址」
 * 变成一种合法的中间状态。
 *
 * 用 {@code record} 而不是实体：它没有身份、不落自己的表，
 * 只是 {@code Attraction.describe} 的入参载体。
 */
public record AttractionCopy(
        String summaryEn,
        String contentEn,
        String ticketInfoEn,
        String openingHoursEn,
        String bookingInfoEn,
        String paymentInfoEn,
        String addressEn,
        String addressCn,
        String transitEn,
        String suggestedDurationEn) {
}
