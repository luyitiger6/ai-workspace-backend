package com.luyi.myfirst.web.attraction.dto;

import com.luyi.myfirst.common.web.ApiResponse;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * 景点列表项与榜单成员的响应体（spec「列表项字段口径」）。
 *
 * 字段集合由 spec 钉死为 11 个：正文与详情专属文案不得出现在这里。
 * {@code city} 承载城市 slug；{@code rating} 用 {@code BigDecimal}（→ JSON number，
 * 未知为 {@code null} 而不是 0）；{@code publishedAt} 用 {@code Instant}（→ ISO 字符串）。
 *
 * 与前端 {@code frontend/lib/contracts/attractions.ts} 的同名 interface 双向比对，
 * 由 10 号闸门负责：改这里必须同步改那一侧。
 */
public record AttractionListItemResponse(
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
        boolean openOnMonday) implements ApiResponse {
}