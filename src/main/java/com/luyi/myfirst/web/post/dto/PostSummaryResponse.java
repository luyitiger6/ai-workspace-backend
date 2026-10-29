package com.luyi.myfirst.web.post.dto;

import com.luyi.myfirst.common.web.ApiResponse;

import java.time.Instant;
import java.util.List;

/**
 * 帖子列表项的响应体：详情白名单的子集，比它少一个 {@code content}。
 *
 * 少字段不破坏白名单（判据是「列表项 ∪ 详情 = 九个字段名」），多字段才破坏。
 * 想加摘要、点赞数、评论数也属于契约变更，不在这里顺手扩。
 */
public record PostSummaryResponse(
        String publicId,
        String title,
        String coverImage,
        List<String> tags,
        String status,
        String authorPublicId,
        Instant createdAt,
        Instant updatedAt) implements ApiResponse {
}
