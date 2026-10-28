package com.luyi.myfirst.service.auth.impl;

import com.luyi.myfirst.common.exception.AuthErrorEnum;
import com.luyi.myfirst.common.exception.BizException;
import com.luyi.myfirst.common.security.PasswordHasher;
import com.luyi.myfirst.dao.auth.entity.Account;
import com.luyi.myfirst.dao.auth.entity.AuthSession;
import com.luyi.myfirst.dao.auth.repository.AccountRepository;
import com.luyi.myfirst.dao.auth.repository.AuthSessionRepository;
import com.luyi.myfirst.service.auth.LoginService;
import com.luyi.myfirst.service.auth.model.IssuedToken;
import com.luyi.myfirst.service.auth.model.LoginResult;
import com.luyi.myfirst.service.auth.support.EmailNormalizer;
import com.luyi.myfirst.service.auth.support.TokenIssuer;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;

/**
 * 登录：先凭证，后状态（D3）。
 *
 * 顺序反了就是漏洞：先判状态的话，攻击者用任意密码探测即可从 {@code 403} 读出
 * 「该邮箱存在且未验证」，邮箱枚举的成本降到一次请求。先验密码则 {@code 403}
 * 只在「密码也是对的」时出现——那时攻击者已经拥有账户访问权，泄漏不再有价值。
 *
 * 来源维度限流（D3 第 0 步）与账户维度要求图形码不在这里：它们要按来源与连续失败
 * 计数判定，属 tasks 5.4 / 5.9 的接线范围。本类只负责「凭证 → 状态」这一段。
 */
@Service
public class LoginServiceImpl implements LoginService {

    private final AccountRepository accounts;
    private final AuthSessionRepository sessions;
    private final PasswordHasher hasher;
    private final TokenIssuer issuer;

    public LoginServiceImpl(AccountRepository accounts,
                            AuthSessionRepository sessions,
                            PasswordHasher hasher,
                            TokenIssuer issuer) {
        this.accounts = accounts;
        this.sessions = sessions;
        this.hasher = hasher;
        this.issuer = issuer;
    }

    /**
     * 完成登录。
     *
     * 邮箱与密码查在同一道出口上：查不到账户与密码不匹配抛出同一个异常，
     * 响应体的状态码、错误码、提示文案因此逐字相同（spec「已注销与凭证错误不可区分」）。
     * 已注销账户不在此口径内（查询条件带 {@code deletedAt IS NULL}），
     * 它落到「查不到」这条路，所以「已注销」与「从未注册」也是同形。
     */
    @Override
    @Transactional
    public LoginResult login(String email, String rawPassword, Instant now) {
        String normalized = EmailNormalizer.normalize(email);
        if (normalized == null || normalized.isBlank() || rawPassword == null) {
            throw new BizException(AuthErrorEnum.VALIDATION_FAILED);
        }

        Account account = accounts.findByEmailAndDeletedAtIsNull(normalized).orElse(null);
        if (account == null || !account.passwordMatches(hasher, rawPassword)) {
            throw new BizException(AuthErrorEnum.UNAUTHENTICATED);
        }

        return switch (account.status()) {
            case ACTIVE -> activeSession(account, now);
            case EMAIL_UNVERIFIED -> throw new BizException(AuthErrorEnum.EMAIL_UNVERIFIED);
            // 查询口径不含已删除行，这个分支按当前实现不可达；保留它是为了让
            // 「响应形态由状态唯一决定」在这里读起来是完整的——日后谁放宽查询口径，
            // 也不会顺带漏掉「已注销仍是 401」这条语义。
            case DELETED -> throw new BizException(AuthErrorEnum.UNAUTHENTICATED);
        };
    }

    private LoginResult activeSession(Account account, Instant now) {
        IssuedToken token = issuer.issue(account, now);
        sessions.save(AuthSession.open(account.id(), token.jti(), token.expiresAt()));
        return new LoginResult(account.publicId(), account.email(), account.displayName(), token);
    }
}
