package com.luyi.myfirst.service.post.interaction.model;

import com.luyi.myfirst.common.enums.VoteDirection;

import java.time.Instant;

/**
 * 投票的服务层视图：对外白名单字段。
 */
public record VoteView(String publicId,
                       String postPublicId,
                       String authorPublicId,
                       VoteDirection direction,
                       Instant createdAt,
                       Instant updatedAt) {
}
