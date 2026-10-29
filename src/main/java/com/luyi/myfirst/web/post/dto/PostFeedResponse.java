package com.luyi.myfirst.web.post.dto;

import com.luyi.myfirst.common.web.ApiResponse;

import java.util.List;

/**
 * 列表的响应外壳：条目 + 续页游标。
 *
 * {@code nextCursor} 为 {@code null} 表示没有下一页。它不是白名单字段的一部分——
 * 白名单约束的是**帖子本身**的字段，分页外壳与 {@code ErrorResponse} 一样是端点结构。
 */
public record PostFeedResponse(List<PostSummaryResponse> items, String nextCursor) implements ApiResponse {
}
