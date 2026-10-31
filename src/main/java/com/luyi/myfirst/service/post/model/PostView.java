package com.luyi.myfirst.service.post.model;

import com.luyi.myfirst.common.enums.PostStatus;

import java.time.Instant;
import java.util.List;

/**
 * 帖子详情的服务层视图：正文与三项有效互动统计均在白名单内。
 *
 * 这里已经是「对外形状」，但仍是服务层类型——web 层的 {@code PostResponse} 逐字镜像它，
 * 目的是让「响应字段集合等于白名单」这条断言可以在服务层先钉一遍（design Risks 第 2 条：
 * {@code ApiResponse} 只是类型归属，没有运行时守卫）。
 *
 * 没有 {@code id} / {@code deletedAt} / {@code version} / {@code createdBy} / {@code updatedBy}：
 * 它们要么是内部主键，要么是审计或并发列，spec「审计与并发字段不外泄」逐条点名。
 * 也**没有** {@code publishedAt}：发布时刻是排序用的内部事实，白名单里不含它。
 */
public record PostView(String publicId,
                       String title,
                       String content,
                       String coverImage,
                       List<String> tags,
                       PostStatus status,
                       String authorPublicId,
                       Instant createdAt,
                       Instant updatedAt,
                       long commentCount,
                       long upVoteCount,
                       long bookmarkCount) {
}
