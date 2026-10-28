package com.luyi.myfirst.service.auth;

import com.luyi.myfirst.service.auth.model.IssuedToken;
import com.luyi.myfirst.service.auth.support.TokenIssuer;
import com.luyi.myfirst.service.auth.support.TokenVerifier;

import com.luyi.myfirst.dao.auth.entity.Account;
import com.luyi.myfirst.dao.auth.entity.AuthSession;
import com.luyi.myfirst.dao.auth.repository.AccountRepository;
import com.luyi.myfirst.dao.auth.repository.AuthSessionRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 三步校验的边界。每个用例只破坏一步，其余两步保持成立——
 * 这样失败时能直接读出是哪一步没守住，而不是「反正都是 401」。
 *
 * 连真实数据库：会话与账户都是要落库的东西，用内存替身测不出
 * 「jti 查不到」与「账户已注销」的真实行为。
 */
@SpringBootTest
@Transactional
class TokenVerifierTest {

    @Autowired
    private TokenVerifier verifier;

    @Autowired
    private TokenIssuer issuer;

    @Autowired
    private AccountRepository accounts;

    @Autowired
    private AuthSessionRepository sessions;

    @Test
    @DisplayName("有效令牌：三步全过，返回对应账户")
    void acceptsValidToken() {
        Account account = persistAccount();
        IssuedToken issued = issueAndRegister(account, Instant.now());

        assertThat(verifier.verify(issued.value()))
                .map(verified -> verified.account().publicId())
                .contains(account.publicId());
    }

    @Test
    @DisplayName("被篡改的令牌：验签失败")
    void rejectsTamperedToken() {
        Account account = persistAccount();
        IssuedToken issued = issueAndRegister(account, Instant.now());

        assertThat(verifier.verify(issued.value() + "x")).isEmpty();
    }

    @Test
    @DisplayName("过期令牌：解析阶段即拒绝，不查会话")
    void rejectsExpiredToken() {
        Account account = persistAccount();
        // 8 天前签发、有效期 7 天——令牌已过期一天
        String token = issuer.issue(account, Instant.now().minus(Duration.ofDays(8))).value();

        assertThat(verifier.verify(token)).isEmpty();
    }

    @Test
    @DisplayName("已吊销的 jti：签名与有效期都对也拒绝")
    void rejectsRevokedSession() {
        Account account = persistAccount();
        IssuedToken issued = issuer.issue(account, Instant.now());
        AuthSession session = sessions.save(AuthSession.open(account.id(), issued.jti(), issued.expiresAt()));
        session.revoke(Instant.now());
        sessions.save(session);

        assertThat(verifier.verify(issued.value())).isEmpty();
    }

    @Test
    @DisplayName("会话未登记：令牌不被承认（吊销集合是白名单）")
    void rejectsUnregisteredSession() {
        Account account = persistAccount();
        String token = issuer.issue(account, Instant.now()).value();

        assertThat(verifier.verify(token)).isEmpty();
    }

    @Test
    @DisplayName("账户已注销：持有效令牌也拒绝")
    void rejectsDeletedAccount() {
        Account account = persistAccount();
        IssuedToken issued = issueAndRegister(account, Instant.now());
        account.delete(Instant.now());
        accounts.save(account);

        assertThat(verifier.verify(issued.value())).isEmpty();
    }

    @Test
    @DisplayName("令牌版本自增：此前签发的全部令牌立即被拒（密码重置走这条路）")
    void rejectsTokensAfterVersionBump() {
        Account account = persistAccount();
        IssuedToken issued = issueAndRegister(account, Instant.now());

        account.revokeAllTokens();
        accounts.save(account);

        assertThat(verifier.verify(issued.value())).isEmpty();
    }

    private Account persistAccount() {
        Account account = Account.createActive(
                UUID.randomUUID().toString().replace("-", "").substring(0, 26),
                "u-" + UUID.randomUUID() + "@example.com",
                "$2a$10$abcdefghijklmnopqrstuv",
                "Alice");
        return accounts.save(account);
    }

    private IssuedToken issueAndRegister(Account account, Instant issuedAt) {
        IssuedToken issued = issuer.issue(account, issuedAt);
        sessions.save(AuthSession.open(account.id(), issued.jti(), issued.expiresAt()));
        return issued;
    }
}
