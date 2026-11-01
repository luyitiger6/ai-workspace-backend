package com.luyi.myfirst.service.attraction.model;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * 详情视图：列表项 11 个字段 + 详情专属 11 个字段，扁平不嵌套（design D4）。
 *
 * 与 {@link AttractionSummary} 刻意重复这 11 个字段：嵌套虽省十几行，
 * 但会让契约端出现 {@code summary.publicId} 这样的前缀解构，
 * 也让 10 号闸门比对面多一层「嵌套 record 是否受管」的判断。
 *
 * 详情不经手 {@link AttractionSummary}：它多出来的 {@code summaryEn} 等字段
 * 全是详情页才读的文案，列表路径不该为了复用而多装配十个属性。
 */
public record AttractionView(String publicId,
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
                             Instant verifiedAt) {
}
