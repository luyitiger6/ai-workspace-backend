package com.luyi.myfirst.common.enums;

import java.util.Optional;

/**
 * 景点两态（spec「只呈现已发布景点」）。
 *
 * 与 {@link PostStatus} 同构，但**不复用它**：类型等于模块边界，景点状态一旦
 * 需要第三个取值（例如 {@code archived}），改动不应牵动已归档的帖子域。
 * 库侧由 {@code ck_attractions_status} 同步钉死，改词表必须先改约束。
 */
public enum AttractionStatus {

    DRAFT("draft"),
    PUBLISHED("published");

    private final String dbValue;

    AttractionStatus(String dbValue) {
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
    public static Optional<AttractionStatus> fromDbValue(String raw) {
        if (raw == null) {
            return Optional.empty();
        }
        for (AttractionStatus status : values()) {
            if (status.dbValue.equals(raw)) {
                return Optional.of(status);
            }
        }
        return Optional.empty();
    }
}
