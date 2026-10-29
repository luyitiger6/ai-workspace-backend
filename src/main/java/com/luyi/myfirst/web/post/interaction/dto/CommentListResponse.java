package com.luyi.myfirst.web.post.interaction.dto;

import com.luyi.myfirst.common.web.ApiResponse;

import java.util.List;

/**
 * 评论列表的响应外壳：条目 + 续页游标。
 *
 * 列表项由 {@link CommentResponse} 表达；外壳本身也实现 {@link ApiResponse} 标记。
 */
public record CommentListResponse(List<CommentResponse> items, String nextCursor) implements ApiResponse {
}
