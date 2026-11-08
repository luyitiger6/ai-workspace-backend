package com.luyi.myfirst.web.post.dto;

import com.luyi.myfirst.common.web.ApiResponse;
import com.luyi.myfirst.service.post.model.PostSummary;

import java.time.Instant;
import java.util.List;

/**
 * 帖子列表项的响应体：详情白名单的子集，叠加三个互动统计。
 *
 * 统计字段是 spec R4 的契约增量，与 {@link PostResponse} 的字段集合不再满足
 * 「列表项 ∪ 详情 = 九个字段」的旧判据；新的白名单判据以 spec 与
 * {@code frontend/lib/contracts/posts.ts} 为准。
 *
 * 映射从服务模型到响应的唯一出口是 {@link #from}：首页 feed 与用户页列表都必须走它，
 * 否则加字段时两份映射会先后漂移。
 */
public record PostSummaryResponse(
        String publicId,
        String title,
        String coverImage,
        List<String> tags,
        String status,
        String authorPublicId,
        Instant createdAt,
        Instant updatedAt,
        long commentCount,
        long upVoteCount,
        long bookmarkCount) implements ApiResponse {

    public static PostSummaryResponse from(PostSummary summary) {
        return new PostSummaryResponse(
                summary.publicId(),
                summary.title(),
                summary.coverImage(),
                summary.tags(),
                summary.status().dbValue(),
                summary.authorPublicId(),
                summary.createdAt(),
                summary.updatedAt(),
                summary.commentCount(),
                summary.upVoteCount(),
                summary.bookmarkCount());
    }
}
