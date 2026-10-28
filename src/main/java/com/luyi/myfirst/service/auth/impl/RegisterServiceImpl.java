package com.luyi.myfirst.service.auth.impl;

import com.luyi.myfirst.common.exception.AuthErrorEnum;
import com.luyi.myfirst.common.exception.BizException;
import com.luyi.myfirst.common.id.Ulid;
import com.luyi.myfirst.common.security.PasswordHasher;
import com.luyi.myfirst.common.security.PasswordRules;
import com.luyi.myfirst.dao.auth.entity.Account;
import com.luyi.myfirst.dao.auth.entity.AuthSession;
import com.luyi.myfirst.dao.auth.repository.AccountRepository;
import com.luyi.myfirst.dao.auth.repository.AuthSessionRepository;
import com.luyi.myfirst.service.auth.RegisterCodeService;
import com.luyi.myfirst.service.auth.RegisterService;
import com.luyi.myfirst.service.auth.model.IssuedToken;
import com.luyi.myfirst.service.auth.model.Registration;
import com.luyi.myfirst.service.auth.support.EmailNormalizer;
import com.luyi.myfirst.service.auth.support.TokenIssuer;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;

/**
 * 注册：验码 → 建账户 → 签发令牌 → 登记会话。
 *
 * 注册即自动登录（spec「注册即完成邮箱验证」）：账户直接落到 active，
 * 令牌随同一次响应以 Set-Cookie 下发，用户不用再填一遍密码。
 *
 * 校验顺序是契约的一部分，而不是随手排的：
 * 1. 邮箱与密码格式在前 —— 密码太短属于表单错误，不该消耗用户手里那张验证码；
 * 2. 验证码其次 —— {@link RegisterCodeService#verify} 一旦通过就删键，它后面不能再有
 *    「本可以提前拒绝」的校验，否则用户要为一次拼写错误重新走完整条取码链路。
 *
 * 唯一键与 Redis 无法同事务：码在 Redis 里删掉之后，账户即便因数据库异常没建成，
 * 这个码也不会回来。这是 spec「比对成功后 MUST 在创建账户的同一处理中删除该键」的直接后果，
 * 代价是可接受的——重新取码要过的仍然是图形码、冷却与日配额，用户并不会因此被卡住。
 */
@Service
public class RegisterServiceImpl implements RegisterService {

    /** display_name 列宽：varchar(64) 计的是字符数。 */
    private static final int DISPLAY_NAME_MAX = 64;

    private final AccountRepository accounts;
    private final AuthSessionRepository sessions;
    private final RegisterCodeService registerCodes;
    private final PasswordHasher hasher;
    private final TokenIssuer issuer;

    public RegisterServiceImpl(AccountRepository accounts,
                               AuthSessionRepository sessions,
                               RegisterCodeService registerCodes,
                               PasswordHasher hasher,
                               TokenIssuer issuer) {
        this.accounts = accounts;
        this.sessions = sessions;
        this.registerCodes = registerCodes;
        this.hasher = hasher;
        this.issuer = issuer;
    }

    /**
     * 完成注册。
     *
     * @param now 签发时刻由调用方给出，测试才能断言 exp 与 iat 的差值等于配置的有效期
     */
    @Override
    @Transactional
    public Registration register(String email, String code, String rawPassword,
                                 String displayName, Instant now) {
        String normalized = EmailNormalizer.normalize(email);
        if (normalized == null || normalized.isBlank()) {
            throw new BizException(AuthErrorEnum.VALIDATION_FAILED);
        }
        if (!PasswordRules.isValid(rawPassword)) {
            throw new BizException(AuthErrorEnum.VALIDATION_FAILED);
        }

        registerCodes.verify(normalized, code);

        // 已注册邮箱必须与「码错误」同形（spec「注册不得泄露邮箱是否已存在」）。
        // 5.2 会让这类邮箱根本收不到码；这里的判断同时兜住并发注册与历史遗留的码。
        if (accounts.findByEmailAndDeletedAtIsNull(normalized).isPresent()) {
            throw new BizException(AuthErrorEnum.INVALID_CODE);
        }

        Account account = accounts.save(Account.createActive(
                Ulid.generate(),
                normalized,
                hasher.hash(rawPassword),
                displayNameOf(displayName, normalized)));

        IssuedToken token = issuer.issue(account, now);
        sessions.save(AuthSession.open(account.id(), token.jti(), token.expiresAt()));

        return new Registration(account.publicId(), account.email(), account.displayName(), token);
    }

    /**
     * 昵称：传了就用传入值（去首尾空白后非空才算传），没传取邮箱 @ 前那一段。
     *
     * 截断点必须避开代理对：varchar(64) 计的是字符，而 String.length() 计 UTF-16 单元，
     * 在 emoji 中间切断会留下半个字符——那个值写进库就成了永远读不回来的脏数据。
     */
    private static String displayNameOf(String raw, String normalizedEmail) {
        String candidate = raw == null ? "" : raw.trim();
        if (candidate.isEmpty()) {
            int at = normalizedEmail.indexOf('@');
            candidate = at > 0 ? normalizedEmail.substring(0, at) : normalizedEmail;
        }
        if (candidate.codePointCount(0, candidate.length()) <= DISPLAY_NAME_MAX) {
            return candidate;
        }
        return candidate.substring(0, candidate.offsetByCodePoints(0, DISPLAY_NAME_MAX));
    }
}
