package com.luyi.myfirst.dao.auth.entity;

import com.luyi.myfirst.common.enums.AccountStatus;
import com.luyi.myfirst.common.exception.IllegalAccountTransitionException;
import com.luyi.myfirst.common.security.PasswordHasher;
import com.luyi.myfirst.dao.auth.converter.AccountStatusConverter;
import jakarta.persistence.Column;
import jakarta.persistence.Convert;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;

import java.time.Instant;

/**
 * 账户主体。
 *
 * 状态只能经具名方法迁移（见 {@link #delete(Instant)}），没有 setStatus：
 * 任何地方直接写字段，「未定义迁移被拒绝」这条 Scenario 就无从测试。
 *
 * 密码哈希不对外提供读方法（D5）：凭证校验以「传入校验器」的方式进行，
 * 哈希不离开本对象，调用方只能拿到是/否。
 */
@Entity
@Table(name = "accounts")
public class Account {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 对外标识：ULID（Crockford Base32，26 字符）。同一邮箱重新注册会拿到新值。 */
    @Column(name = "public_id", nullable = false, length = 26)
    private String publicId;

    @Column(nullable = false, length = 255)
    private String email;

    @Column(name = "password_hash", nullable = false, length = 255)
    private String passwordHash;

    @Column(name = "display_name", nullable = false, length = 64)
    private String displayName;

    @Convert(converter = AccountStatusConverter.class)
    @Column(nullable = false, length = 16)
    private AccountStatus status;

    @Column(name = "token_version", nullable = false)
    private int tokenVersion;

    @Column(name = "deleted_at")
    private Instant deletedAt;

    /** 乐观锁列；schema.sql 有 DEFAULT 0 与 ck_accounts_version >= 0。 */
    @Version
    @Column(nullable = false)
    private int version;

    /** 库侧默认值填充，Java 端只读。 */
    @Column(name = "created_at", insertable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", insertable = false, updatable = false)
    private Instant updatedAt;

    protected Account() {
        // JPA 需要
    }

    /**
     * 建立一个 active 账户行。注册即 active——本变更没有把账户放进 email_unverified 的入口。
     */
    public static Account createActive(String publicId, String email, String passwordHash, String displayName) {
        Account account = new Account();
        account.publicId = publicId;
        account.email = email;
        account.passwordHash = passwordHash;
        account.displayName = displayName;
        account.status = AccountStatus.ACTIVE;
        account.tokenVersion = 0;
        return account;
    }

    /**
     * 本人注销：active -> deleted 的唯一入口。
     *
     * {@code deletedAt} 必须与状态同时落库：active_email 生成列靠它判定该行是否仍占用邮箱，
     * 只改 status 会让同一邮箱永远撞唯一键，注销后重注册无法实现。
     */
    public void delete(Instant occurredAt) {
        if (!status.canTransitionTo(AccountStatus.DELETED)) {
            throw new IllegalAccountTransitionException(status, AccountStatus.DELETED);
        }
        this.status = AccountStatus.DELETED;
        this.deletedAt = occurredAt;
    }

    /**
     * 拿外部提交的明文与已存哈希比对。
     * 哈希不出实体：调用方只能拿到是/否，拿不到 passwordHash 本身（D5）。
     */
    public boolean passwordMatches(PasswordHasher hasher, String rawPassword) {
        return hasher.matches(rawPassword, this.passwordHash);
    }

    public Long id() {
        return id;
    }

    public String publicId() {
        return publicId;
    }

    public String email() {
        return email;
    }

    public String displayName() {
        return displayName;
    }

    public AccountStatus status() {
        return status;
    }

    public int tokenVersion() {
        return tokenVersion;
    }

    public Instant deletedAt() {
        return deletedAt;
    }

    public int version() {
        return version;
    }

    public Instant createdAt() {
        return createdAt;
    }

    public Instant updatedAt() {
        return updatedAt;
    }
}
