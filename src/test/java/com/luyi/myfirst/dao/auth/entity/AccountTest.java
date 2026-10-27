package com.luyi.myfirst.dao.auth.entity;

import com.luyi.myfirst.common.enums.AccountStatus;
import com.luyi.myfirst.common.exception.IllegalAccountTransitionException;
import com.luyi.myfirst.common.security.BcryptPasswordHasher;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 唯一的具名迁移 delete() 的合法与非法入口。
 */
class AccountTest {

    private static final String PUBLIC_ID = "01JBXRZ8QK0000000000000000";
    private static final String EMAIL = "alice@example.com";
    private static final String PASSWORD_HASH = "$2a$10$abcdefghijklmnopqrstuv";
    private static final Instant NOW = Instant.parse("2026-10-27T08:00:00Z");

    @Test
    @DisplayName("合法入口：active 账户注销后进入 deleted 并记录注销时间")
    void activeAccountCanBeDeleted() {
        Account account = accountWithStatus(AccountStatus.ACTIVE);

        account.delete(NOW);

        assertThat(account.status()).isEqualTo(AccountStatus.DELETED);
        assertThat(account.deletedAt()).isEqualTo(NOW);
    }

    @Test
    @DisplayName("非法入口：已注销账户不能再次注销，状态与注销时间都不被改写")
    void deletedAccountCannotBeDeletedAgain() {
        Account account = accountWithStatus(AccountStatus.ACTIVE);
        account.delete(NOW);

        Instant later = NOW.plusSeconds(86_400);

        assertThatThrownBy(() -> account.delete(later))
                .isInstanceOf(IllegalAccountTransitionException.class)
                .hasMessageContaining("deleted");

        assertThat(account.status()).isEqualTo(AccountStatus.DELETED);
        assertThat(account.deletedAt()).isEqualTo(NOW);
    }

    @Test
    @DisplayName("非法入口：email_unverified 账户注销被拒——本变更没有离开该状态的出口")
    void emailUnverifiedAccountCannotBeDeleted() {
        Account account = accountWithStatus(AccountStatus.EMAIL_UNVERIFIED);

        assertThatThrownBy(() -> account.delete(NOW))
                .isInstanceOf(IllegalAccountTransitionException.class);

        assertThat(account.status()).isEqualTo(AccountStatus.EMAIL_UNVERIFIED);
        assertThat(account.deletedAt()).isNull();
    }

    @Test
    @DisplayName("新建账户即 active，没有把账户置入 email_unverified 的工厂方法")
    void createActiveStartsAtActive() {
        Account account = Account.createActive(PUBLIC_ID, EMAIL, PASSWORD_HASH, "Alice");

        assertThat(account.status()).isEqualTo(AccountStatus.ACTIVE);
        assertThat(account.deletedAt()).isNull();
        assertThat(account.tokenVersion()).isZero();
    }

    @Test
    @DisplayName("注销同时让令牌版本自增：此前签发的全部令牌作废")
    void deleteRevokesAllTokens() {
        Account account = accountWithStatus(AccountStatus.ACTIVE);
        int before = account.tokenVersion();

        account.delete(NOW);

        assertThat(account.tokenVersion()).isEqualTo(before + 1);
    }

    @Test
    @DisplayName("密码重置走同一条路：revokeAllTokens 让旧令牌的 ver 落后")
    void revokeAllTokensBumpsVersion() {
        Account account = Account.createActive(PUBLIC_ID, EMAIL, PASSWORD_HASH, "Alice");

        account.revokeAllTokens();

        assertThat(account.tokenVersion()).isEqualTo(1);
    }

    @Test
    @DisplayName("密码只以哈希持久化：字段不含明文，哈希可校验、错误密码被拒")
    void passwordIsPersistedAsHashOnly() throws Exception {
        BcryptPasswordHasher hasher = new BcryptPasswordHasher();
        String rawPassword = "correct-horse-battery";

        Account account = Account.createActive(PUBLIC_ID, EMAIL, hasher.hash(rawPassword), "Alice");

        String stored = readPasswordHash(account);
        assertThat(stored).isNotEqualTo(rawPassword);
        assertThat(stored).doesNotContain(rawPassword);
        assertThat(stored).startsWith("$2");

        assertThat(account.passwordMatches(hasher, rawPassword)).isTrue();
        assertThat(account.passwordMatches(hasher, rawPassword + "-x")).isFalse();
    }

    /**
     * 直接读持久化字段：这是「库内只能读到哈希」的最近似断言。
     * 生产代码不提供该读方法，反射只存在于测试侧。
     */
    private static String readPasswordHash(Account account) throws ReflectiveOperationException {
        Field field = Account.class.getDeclaredField("passwordHash");
        field.setAccessible(true);
        return (String) field.get(account);
    }

    /**
     * email_unverified 只能由测试夹具造出（spec 明确该状态生产不可达）。
     * 反射放在测试里，生产代码不暴露置入入口。
     */
    private static Account accountWithStatus(AccountStatus status) {
        Account account = Account.createActive(PUBLIC_ID, EMAIL, PASSWORD_HASH, "Alice");
        if (status == AccountStatus.ACTIVE) {
            return account;
        }
        try {
            Field field = Account.class.getDeclaredField("status");
            field.setAccessible(true);
            field.set(account, status);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("无法构造状态夹具", e);
        }
        return account;
    }
}
