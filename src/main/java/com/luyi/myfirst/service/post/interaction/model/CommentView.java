package com.luyi.myfirst.service.post.interaction.model;

import java.time.Instant;

/**
 * 评论的服务层视图：对外白名单字段。
 */
public record CommentView(String publicId,
                          String postPublicId,
                          String parentPublicId,
                          String authorPublicId,
                          String content,
                          Instant createdAt,
                          Instant updatedAt) {
}
