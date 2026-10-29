package com.luyi.myfirst.web.post.interaction.dto;

import com.luyi.myfirst.common.web.ApiResponse;

import java.time.Instant;

/**
 * 投票详情响应体：对外白名单字段。
 *
 * {@code direction} 以字符串 {@code up}/{@code down} 返回，与契约一致。
 */
public record VoteResponse(
        String publicId,
        String postPublicId,
        String authorPublicId,
        String direction,
        Instant createdAt,
        Instant updatedAt) implements ApiResponse {
}
