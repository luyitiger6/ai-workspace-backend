package com.luyi.myfirst.web.post.dto;

import com.luyi.myfirst.common.web.ApiResponse;

import java.time.Instant;
import java.util.List;

/**
 * 帖子详情的响应体：九个字段就是白名单本身（spec「响应字段为白名单且不含实体」）。
 *
 * {@code status} 用字符串而不是枚举：契约里写的是 {@code draft} / {@code published}，
 * 与库内 CHECK 取值一致；交给 Jackson 直接序列化枚举会得到 {@code PUBLISHED}。
 *
 * 没有 {@code id} / {@code deletedAt} / {@code version} / {@code createdBy} / {@code updatedBy}，
 * 也没有 {@code publishedAt}——这些字段不是「忘了加」，是白名单明确排除的。
 */
public record PostResponse(
        String publicId,
        String title,
        String content,
        String coverImage,
        List<String> tags,
        String status,
        String authorPublicId,
        Instant createdAt,
        Instant updatedAt) implements ApiResponse {
}
