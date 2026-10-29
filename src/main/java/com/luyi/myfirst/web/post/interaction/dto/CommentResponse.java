package com.luyi.myfirst.web.post.interaction.dto;

import com.luyi.myfirst.common.web.ApiResponse;

import java.time.Instant;

/**
 * 评论详情响应体：对外白名单字段。
 *
 * 不含内部主键、删除时间、版本号、创建人/更新人内部主键（spec「互动响应字段为白名单」）。
 */
public record CommentResponse(
        String publicId,
        String postPublicId,
        String parentPublicId,
        String authorPublicId,
        String content,
        Instant createdAt,
        Instant updatedAt) implements ApiResponse {
}
