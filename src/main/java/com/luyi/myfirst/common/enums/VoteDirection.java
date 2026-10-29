package com.luyi.myfirst.common.enums;

import java.util.Optional;

/**
 * 投票方向（spec「direction 取值为 up 或 down」）。
 *
 * 库侧由 {@code ck_post_votes_direction} 同步钉死，改词表必须先改约束。
 */
public enum VoteDirection {

    UP("up"),
    DOWN("down");

    private final String dbValue;

    VoteDirection(String dbValue) {
        this.dbValue = dbValue;
    }

    /** 与 schema.sql 中 CHECK 约束的取值一一对应。 */
    public String dbValue() {
        return dbValue;
    }

    /** 按库内字符串值查找枚举，未知值返回 {@link Optional#empty()}。 */
    public static Optional<VoteDirection> fromDbValue(String raw) {
        if (raw == null) {
            return Optional.empty();
        }
        for (VoteDirection direction : values()) {
            if (direction.dbValue.equals(raw)) {
                return Optional.of(direction);
            }
        }
        return Optional.empty();
    }
}
