package com.luyi.myfirst.web.post.interaction.dto;

import com.luyi.myfirst.common.web.ApiResponse;

import java.time.Instant;

/**
 * 收藏详情响应体：对外白名单字段。
 */
public record BookmarkResponse(
        String publicId,
        String postPublicId,
        String authorPublicId,
        Instant createdAt,
        Instant updatedAt) implements ApiResponse {
}
