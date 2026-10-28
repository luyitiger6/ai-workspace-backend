package com.luyi.myfirst.service.auth.support;

import com.luyi.myfirst.config.AuthProperties;
import com.luyi.myfirst.dao.auth.entity.Account;
import com.luyi.myfirst.service.auth.model.IssuedToken;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.springframework.stereotype.Component;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Date;
import java.util.UUID;

/**
 * 令牌签发：HS256，载荷 sub(publicId) / ver(令牌版本) / jti / iat / exp（D8）。
 *
 * 有效期是绝对的 {@code session-ttl}，不滑动续期：每次请求都算一次过期，
 * 续期就要再写 cookie，而这会把「读接口」变成「写状态」的操作。
 *
 * 令牌只经 cookie 下发——本类不提供任何可直接塞进响应体的结构，
 * 调用方拿到 {@link IssuedToken} 后只能交给认证 cookie 的唯一写入点（web 层的 AuthCookie）。
 */
@Component
public class TokenIssuer {

    private final SecretKey key;
    private final AuthProperties properties;

    public TokenIssuer(AuthProperties properties) {
        this.properties = properties;
        // 密钥短于 32 字节时 Keys.hmacShaKeyFor 直接抛 WeakKeyException：
        // 启动失败好过安静地签出弱签名令牌。
        this.key = Keys.hmacShaKeyFor(properties.jwtSecret().getBytes(StandardCharsets.UTF_8));
    }

    /**
     * 为账户签一张新令牌。
     *
     * @param issuedAt 签发时刻由调用方给出，而不是在方法里取 now：
     *                 否则测试无法断言 exp 与 iat 的差值恰好是配置的有效期
     */
    public IssuedToken issue(Account account, Instant issuedAt) {
        Instant expiresAt = issuedAt.plus(properties.sessionTtl());
        String jti = UUID.randomUUID().toString();
        String value = Jwts.builder()
                .subject(account.publicId())
                .claim("ver", account.tokenVersion())
                .id(jti)
                .issuedAt(Date.from(issuedAt))
                .expiration(Date.from(expiresAt))
                .signWith(key, Jwts.SIG.HS256)
                .compact();
        return new IssuedToken(value, jti, expiresAt);
    }
}
