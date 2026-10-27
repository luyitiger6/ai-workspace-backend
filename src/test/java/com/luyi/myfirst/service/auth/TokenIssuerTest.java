package com.luyi.myfirst.service.auth;

import com.luyi.myfirst.config.AuthProperties;
import com.luyi.myfirst.dao.auth.entity.Account;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import io.jsonwebtoken.security.WeakKeyException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Date;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 签发契约：载荷字段、有效期、jti 唯一性。
 *
 * 这里用独立密钥验签而不是复用生产密钥的解析器，
 * 目的是让「令牌确实是用配置里的密钥签的」成为断言，而不是运行时才发现的巧合。
 */
class TokenIssuerTest {

    private static final String SECRET = "0123456789abcdef0123456789abcdef";
    /** JWT 的 iat / exp 是秒级精度，签发时刻先截断到秒，断言才能做等值比较。 */
    private static final Instant ISSUED_AT = Instant.now().truncatedTo(ChronoUnit.SECONDS);

    @Test
    @DisplayName("载荷含 sub / ver / jti / iat / exp，有效期恰为 session-ttl")
    void issuesTokenWithDeclaredClaims() {
        TokenIssuer issuer = new TokenIssuer(properties(SECRET, Duration.ofDays(7)));
        Account account = newAccount();

        IssuedToken issued = issuer.issue(account, ISSUED_AT);
        Claims claims = parse(issued.value());

        assertThat(claims.getSubject()).isEqualTo(account.publicId());
        assertThat(claims.get("ver", Integer.class)).isEqualTo(account.tokenVersion());
        assertThat(claims.getId()).isEqualTo(issued.jti());
        assertThat(claims.getIssuedAt()).isEqualTo(Date.from(ISSUED_AT));
        assertThat(claims.getExpiration()).isEqualTo(Date.from(ISSUED_AT.plus(Duration.ofDays(7))));
        assertThat(issued.expiresAt()).isEqualTo(ISSUED_AT.plus(Duration.ofDays(7)));
    }

    @Test
    @DisplayName("两次签发得到不同 jti：两张令牌才能被分别吊销")
    void generatesUniqueJtiPerToken() {
        TokenIssuer issuer = new TokenIssuer(properties(SECRET, Duration.ofDays(7)));
        Account account = newAccount();

        IssuedToken first = issuer.issue(account, ISSUED_AT);
        IssuedToken second = issuer.issue(account, ISSUED_AT);

        assertThat(first.jti()).isNotEqualTo(second.jti());
        assertThat(first.value()).isNotEqualTo(second.value());
    }

    @Test
    @DisplayName("有效期跟着配置走，不是写死的 7 天")
    void honorsConfiguredTtl() {
        TokenIssuer issuer = new TokenIssuer(properties(SECRET, Duration.ofHours(8)));

        IssuedToken issued = issuer.issue(newAccount(), ISSUED_AT);

        assertThat(parse(issued.value()).getExpiration())
                .isEqualTo(Date.from(ISSUED_AT.plus(Duration.ofHours(8))));
    }

    @Test
    @DisplayName("密钥短于 256 位：构造即失败，不签出弱签名令牌")
    void rejectsWeakSecret() {
        assertThatThrownBy(() -> new TokenIssuer(properties("too-short", Duration.ofDays(7))))
                .isInstanceOf(WeakKeyException.class);
    }

    private static Claims parse(String token) {
        SecretKey key = Keys.hmacShaKeyFor(SECRET.getBytes(StandardCharsets.UTF_8));
        return Jwts.parser().verifyWith(key).build().parseSignedClaims(token).getPayload();
    }

    private static Account newAccount() {
        return Account.createActive("A".repeat(26), "alice@example.com",
                "$2a$10$abcdefghijklmnopqrstuv", "Alice");
    }

    private static AuthProperties properties(String secret, Duration sessionTtl) {
        return new AuthProperties(
                sessionTtl,
                secret,
                false,
                List.of("http://localhost:3000"),
                new AuthProperties.Captcha(Duration.ofMinutes(2)),
                new AuthProperties.EmailCode(Duration.ofMinutes(5), Duration.ofSeconds(60), 5, 5),
                new AuthProperties.Login(5, Duration.ofMinutes(10), 20, Duration.ofSeconds(60)));
    }
}
