package com.luyi.myfirst.service.post.interaction.model;

import java.util.List;

/**
 * 评论列表的一页：条目 + 续页游标。
 */
public record CommentPage(List<CommentView> items, String nextCursor) {
}
