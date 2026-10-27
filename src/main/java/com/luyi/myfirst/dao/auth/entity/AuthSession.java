package com.luyi.myfirst.dao.auth.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;

/**
 * 会话与吊销记录。
 *
 * 令牌明文不落库，这里只留 jti：校验时拿载荷里的 jti 查一行，
 * 有且未吊销才算通过。登出、注销、重置密码都通过写 revoked_at
 * 或让账户 token_version 自增来使旧令牌失效（后者不需要逐条吊销）。
 */
@Entity
@Table(name = "auth_sessions")
public class AuthSession {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** JWT 的 jti，36 字符 UUID 文本。 */
    @Column(nullable = false, length = 36)
    private String jti;

    @Column(name = "account_id", nullable = false)
    private Long accountId;

    /** 库侧 DEFAULT CURRENT_TIMESTAMP(3) 填充，Java 端只读。 */
    @Column(name = "issued_at", insertable = false, updatable = false)
    private Instant issuedAt;

    @Column(name = "expires_at", nullable = false)
    private Instant expiresAt;

    @Column(name = "revoked_at")
    private Instant revokedAt;

    protected AuthSession() {
        // JPA 需要
    }

    /**
     * 登记一次签发。
     *
     * expiresAt 取令牌自身的到期时刻而不是「现在 + TTL」：两处各算一次，
     * 迟早出现「令牌已过期但会话还算有效」的错位。
     */
    public static AuthSession open(long accountId, String jti, Instant expiresAt) {
        AuthSession session = new AuthSession();
        session.accountId = accountId;
        session.jti = jti;
        session.expiresAt = expiresAt;
        return session;
    }

    /** 吊销：只写时间戳，不删行——删了就无法回答「这个 jti 到底有没有登记过」。 */
    public void revoke(Instant occurredAt) {
        this.revokedAt = occurredAt;
    }

    public boolean isRevoked() {
        return revokedAt != null;
    }

    public Long id() {
        return id;
    }

    public String jti() {
        return jti;
    }

    public Long accountId() {
        return accountId;
    }

    public Instant issuedAt() {
        return issuedAt;
    }

    public Instant expiresAt() {
        return expiresAt;
    }

    public Instant revokedAt() {
        return revokedAt;
    }
}
