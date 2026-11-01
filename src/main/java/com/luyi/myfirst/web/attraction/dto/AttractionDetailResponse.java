package com.luyi.myfirst.web.attraction.dto;

import com.luyi.myfirst.common.web.ApiResponse;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * 景点详情响应体（spec「详情返回完整决策字段」）。
 *
 * 列表项 11 个字段 + 详情专属 11 个字段，**扁平不嵌套**（design D4）：
 * 前端直接取字段，10 号闸门逐字段双向比对，不用处理 {@code summary.} 前缀。
 *
 * 详情专属部分回答的是「能不能去、要不要预约、怎么付款、怎么给司机看」：
 * {@code ticketInfoEn} / {@code openingHoursEn} / {@code bookingInfoEn} /
 * {@code paymentInfoEn} / {@code addressEn} / {@code addressCn} / {@code transitEn} /
 * {@code suggestedDurationEn}。
 *
 * {@code addressCn} 是唯一允许出现中文的字段，且只出现在详情；列表项里没有它。
 * {@code verifiedAt} 非空是可见性前提——仓储层已把它写成查询条件，
 * 因此本字段在这里恒有值，前端据它渲染 {@code Checked <Mon YYYY>}。
 */
public record AttractionDetailResponse(
        String publicId,
        String nameEn,
        String nameCn,
        String city,
        String coverUrl,
        BigDecimal rating,
        long viewCount,
        Instant publishedAt,
        boolean freeEntry,
        boolean bookingRequired,
        boolean openOnMonday,
        String summaryEn,
        String contentEn,
        String ticketInfoEn,
        String openingHoursEn,
        String bookingInfoEn,
        String paymentInfoEn,
        String addressEn,
        String addressCn,
        String transitEn,
        String suggestedDurationEn,
        Instant verifiedAt) implements ApiResponse {
}