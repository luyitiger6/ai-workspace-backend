package com.luyi.myfirst.web.post.dto;

import com.luyi.myfirst.common.web.ApiResponse;

import java.time.Instant;
import java.util.List;

/**
 * 帖子列表项的响应体：详情白名单的子集，叠加三个互动统计。
 *
 * 统计字段是 spec R4 的契约增量，与 {@link PostResponse} 的字段集合不再满足
 * 「列表项 ∪ 详情 = 九个字段」的旧判据；新的白名单判据以 spec 与
 * {@code frontend/lib/contracts/posts.ts} 为准。
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
}
