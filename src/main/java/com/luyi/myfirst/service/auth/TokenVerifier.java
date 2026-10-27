package com.luyi.myfirst.service.auth;

import com.luyi.myfirst.common.enums.AccountStatus;
import com.luyi.myfirst.config.AuthProperties;
import com.luyi.myfirst.dao.auth.entity.Account;
import com.luyi.myfirst.dao.auth.entity.AuthSession;
import com.luyi.myfirst.dao.auth.repository.AccountRepository;
import com.luyi.myfirst.dao.auth.repository.AuthSessionRepository;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.springframework.stereotype.Component;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.util.Optional;

/**
 * 受保护请求的令牌校验：验签与过期、jti 未被吊销、账户 active 且 ver 一致（D8）。
 *
 * 三步任一不过都返回 empty。失败原因不往外区分——调用方只需要知道「无效」，
 * 区分「签名错」还是「账户已注销」就给攻击者提供了探测面。
 *
 * 校验顺序与实现顺序一致：先算最便宜也最硬的签名，
 * 再查会话（一次索引命中），最后查账户。
 */
@Component
public class TokenVerifier {

    private final SecretKey key;
    private final AccountRepository accounts;
    private final AuthSessionRepository sessions;

    public TokenVerifier(AuthProperties properties,
                         AccountRepository accounts,
                         AuthSessionRepository sessions) {
        this.key = Keys.hmacShaKeyFor(properties.jwtSecret().getBytes(StandardCharsets.UTF_8));
        this.accounts = accounts;
        this.sessions = sessions;
    }

    public Optional<VerifiedToken> verify(String token) {
        Claims claims;
        try {
            // 过期在这里抛 ExpiredJwtException，篡改抛 SignatureException，都归为无效
            claims = Jwts.parser().verifyWith(key).build().parseSignedClaims(token).getPayload();
        } catch (JwtException | IllegalArgumentException e) {
            return Optional.empty();
        }

        String jti = claims.getId();
        Integer version = claims.get("ver", Integer.class);
        if (jti == null || version == null || claims.getSubject() == null) {
            return Optional.empty();
        }

        AuthSession session = sessions.findByJti(jti).orElse(null);
        if (session == null || session.isRevoked()) {
            return Optional.empty();
        }

        Account account = accounts.findByPublicId(claims.getSubject()).orElse(null);
        if (account == null
                || account.status() != AccountStatus.ACTIVE
                || account.tokenVersion() != version) {
            return Optional.empty();
        }
        return Optional.of(new VerifiedToken(account, jti));
    }
}
