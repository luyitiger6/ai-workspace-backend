package com.luyi.myfirst.common.enums;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 状态机边界：九种「源 -> 目标」组合里只允许 active -> deleted 一种。
 *
 * 用全组合遍历而不是逐条列用例——将来新增状态时，这条断言会自动把新边也判成非法，
 * 迫使改动者回到 spec 补迁移图，而不是让一条没人写测试的边悄悄溜过去。
 */
class AccountStatusTest {

    @Test
    @DisplayName("枚举值与 schema.sql 的 CHECK 取值一一对应")
    void dbValueMatchesSchemaCheck() {
        assertThat(AccountStatus.values())
                .extracting(AccountStatus::dbValue)
                .containsExactly("active", "email_unverified", "deleted");
    }

    @Test
    @DisplayName("九种组合里只有 active -> deleted 被允许")
    void onlyActiveToDeletedIsAllowed() {
        for (AccountStatus from : AccountStatus.values()) {
            for (AccountStatus to : AccountStatus.values()) {
                boolean defined = from == AccountStatus.ACTIVE && to == AccountStatus.DELETED;
                assertThat(from.canTransitionTo(to))
                        .as("%s -> %s", from, to)
                        .isEqualTo(defined);
            }
        }
    }

    @Test
    @DisplayName("deleted 是终态：没有任何出边")
    void deletedHasNoOutgoingEdge() {
        for (AccountStatus target : AccountStatus.values()) {
            assertThat(AccountStatus.DELETED.canTransitionTo(target))
                    .as("deleted -> %s", target)
                    .isFalse();
        }
    }

    @Test
    @DisplayName("email_unverified 在本变更内既进不去也出不来")
    void emailUnverifiedIsIsolated() {
        for (AccountStatus source : AccountStatus.values()) {
            assertThat(source.canTransitionTo(AccountStatus.EMAIL_UNVERIFIED))
                    .as("%s -> email_unverified", source)
                    .isFalse();
        }
        for (AccountStatus target : AccountStatus.values()) {
            assertThat(AccountStatus.EMAIL_UNVERIFIED.canTransitionTo(target))
                    .as("email_unverified -> %s", target)
                    .isFalse();
        }
    }
}
