package com.luyi.myfirst.common.enums;

import java.util.Optional;

/**
 * 帖子两态（spec「状态词表只有两值」）。
 *
 * 只有 {@code draft} 与 {@code published}：「下架」由软删除表达，
 * 不存在第三个取值，也不存在 {@code removed} / {@code deleted} 这类状态值。
 * 库侧由 {@code ck_posts_status} 同步钉死，改词表必须先改约束。
 */
public enum PostStatus {

    DRAFT("draft"),
    PUBLISHED("published");

    private final String dbValue;

    PostStatus(String dbValue) {
        this.dbValue = dbValue;
    }

    /** 与 schema.sql 中 CHECK 约束的取值一一对应，不是枚举名的大小写转换。 */
    public String dbValue() {
        return dbValue;
    }

    /**
     * 按库内字符串值查找枚举，未知值返回 {@link Optional#empty()}。
     *
     * 网络请求里的 status 字符串不能直接 {@code valueOf()}：
     * 枚举名是 {@code PUBLISHED}，而契约里写的是 {@code published}。
     */
    public static Optional<PostStatus> fromDbValue(String raw) {
        if (raw == null) {
            return Optional.empty();
        }
        for (PostStatus status : values()) {
            if (status.dbValue.equals(raw)) {
                return Optional.of(status);
            }
        }
        return Optional.empty();
    }
}
