package com.luyi.myfirst.service.post.model;

import java.util.List;

/**
 * 一页列表：条目 + 续页游标。
 *
 * {@code nextCursor} 为 {@code null} 表示没有更多数据——不用空串表达「到底了」，
 * 空串与「从第一页开始」在前端难以区分。游标本身对调用方不透明（design D8）。
 */
public record PostPage(List<PostSummary> items, String nextCursor) {
}
