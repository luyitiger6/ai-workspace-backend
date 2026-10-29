package com.luyi.myfirst.common.enums;

import java.util.Optional;

/**
 * 帖子列表的排序维度（design D4）。
 *
 * 取值与 {@link com.luyi.myfirst.web.post.dto.PostFeedResponse}、
 * {@code frontend/lib/contracts/posts.ts} 的 {@code PostSortBy} 字面量逐字一致，
 * 变更时必须同步三处真源。
 */
public enum PostSortBy {

    PUBLISHED_AT("publishedAt"),
    UP_VOTE_COUNT("upVoteCount"),
    COMMENT_COUNT("commentCount");

    private final String dbValue;

    PostSortBy(String dbValue) {
        this.dbValue = dbValue;
    }

    /** 与前端查询参数、游标首段一一对应。 */
    public String dbValue() {
        return dbValue;
    }

    /** 按字符串值查找枚举；未知值返回 {@link Optional#empty()}。 */
    public static Optional<PostSortBy> fromDbValue(String raw) {
        if (raw == null || raw.isBlank()) {
            return Optional.empty();
        }
        for (PostSortBy sort : values()) {
            if (sort.dbValue.equals(raw)) {
                return Optional.of(sort);
            }
        }
        return Optional.empty();
    }
}
