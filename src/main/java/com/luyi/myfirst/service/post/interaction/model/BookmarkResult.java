package com.luyi.myfirst.service.post.interaction.model;

/**
 * 收藏结果：视图 + 是否为新建（控制器据此返回 201/200）。
 */
public record BookmarkResult(boolean created, BookmarkView view) {
}
