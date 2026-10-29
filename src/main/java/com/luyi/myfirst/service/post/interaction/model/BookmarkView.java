package com.luyi.myfirst.service.post.interaction.model;

import java.time.Instant;

/**
 * 收藏的服务层视图：对外白名单字段。
 */
public record BookmarkView(String publicId,
                           String postPublicId,
                           String authorPublicId,
                           Instant createdAt,
                           Instant updatedAt) {
}
