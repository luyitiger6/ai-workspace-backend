package com.luyi.myfirst.service.attraction.model;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * 列表项与榜单成员的视图（spec「列表项字段口径」的 11 个字段）。
 *
 * 不含正文与详情专属文案：列表页不读全文，而 {@code content_en} 是 MEDIUMTEXT，
 * 一页 50 条就能拉出上百 KiB 的无用负载。
 *
 * {@code city} 直接承载库里的 {@code city_slug}：对外就是城市 slug，
 * 不需要再翻译成本地化城市名——前端用它做筛选与展示的映射。
 */
public record AttractionSummary(String publicId,
                                String nameEn,
                                String nameCn,
                                String city,
                                String coverUrl,
                                BigDecimal rating,
                                long viewCount,
                                Instant publishedAt,
                                boolean freeEntry,
                                boolean bookingRequired,
                                boolean openOnMonday) {
}