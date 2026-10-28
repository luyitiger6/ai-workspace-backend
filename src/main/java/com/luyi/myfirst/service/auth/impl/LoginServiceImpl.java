package com.luyi.myfirst.service.auth.impl;

import com.luyi.myfirst.common.exception.AuthErrorEnum;
import com.luyi.myfirst.common.exception.BizException;
import com.luyi.myfirst.common.security.PasswordHasher;
import com.luyi.myfirst.dao.auth.entity.Account;
import com.luyi.myfirst.dao.auth.entity.AuthSession;
import com.luyi.myfirst.dao.auth.repository.AccountRepository;
import com.luyi.myfirst.dao.auth.repository.AuthSessionRepository;
import com.luyi.myfirst.service.auth.CaptchaService;
import com.luyi.myfirst.service.auth.LoginService;
import com.luyi.myfirst.service.auth.model.IssuedToken;
import com.luyi.myfirst.service.auth.model.LoginAttempt;
import com.luyi.myfirst.service.auth.model.LoginResult;
import com.luyi.myfirst.service.auth.support.EmailNormalizer;
import com.luyi.myfirst.service.auth.support.LoginThrottle;
import com.luyi.myfirst.service.auth.support.TokenIssuer;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;

/**
 * 登录：先限流，再凭证，后状态（D3）。
 *
 * 顺序反了就是漏洞：先判状态的话，攻击者用任意密码探测即可从 {@code 403} 读出
 * 「该邮箱存在且未验证」，邮箱枚举的成本降到一次请求。先验密码则 {@code 403}
 * 只在「密码也是对的」时出现——那时攻击者已经拥有账户访问权，泄漏不再有价值。
 *
 * 两道前置拒绝站在最外面：来源限流先于一切（否则限流对爆破无效），
 * 账户维度图形码先于密码比对（否则一张码就换来一次密码尝试）。
 *
 * 账户不存在时也要付一次 bcrypt 的钱：响应体可以做到逐字相同，响应时间做不到——
 * 「查不到就返回」与「查到了但密码不对」相差几十毫秒，足够逐个试出哪些邮箱注册过。
 */
@Service
public class LoginServiceImpl implements LoginService {

    /** 仅用于在构造期生成一个与真实账户同样强度的比对目标，从不作为任何账户的密码。 */
    private static final String TIMING_EQUALIZATION_SEED = "wc-timing-equalization";

    private final AccountRepository accounts;
    private final AuthSessionRepository sessions;
    private final PasswordHasher hasher;
    private final TokenIssuer issuer;
    private final LoginThrottle throttle;
    private final CaptchaService captcha;

    /**
     * 账户不存在时的比对目标：构造期用同一个 hasher 生成一次。
     *
     * 不硬编码一个 bcrypt 串：那个串会把 cost 写死在代码里，日后调强度或换算法时，
     * 「账户存在」与「账户不存在」两条路径的耗时会再次分叉，而分叉本身就是计时信道。
     */
    private final String timingEqualizationHash;

    public LoginServiceImpl(AccountRepository accounts,
                            AuthSessionRepository sessions,
                            PasswordHasher hasher,
                            TokenIssuer issuer,
                            LoginThrottle throttle,
                            CaptchaService captcha) {
        this.accounts = accounts;
        this.sessions = sessions;
        this.hasher = hasher;
        this.issuer = issuer;
        this.throttle = throttle;
        this.captcha = captcha;
        this.timingEqualizationHash = hasher.hash(TIMING_EQUALIZATION_SEED);
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
    public LoginResult login(LoginAttempt attempt, Instant now) {
        // 0. 来源维度：对凭证正确的请求同样生效，否则限流对爆破无效。
        if (throttle.isSourceBlocked(attempt.sourceKey())) {
            throw new BizException(AuthErrorEnum.RATE_LIMITED,
                    throttle.sourceRetryAfter().toSeconds());
        }

        String normalized = EmailNormalizer.normalize(attempt.email());
        if (normalized == null || normalized.isBlank() || attempt.rawPassword() == null) {
            throw new BizException(AuthErrorEnum.VALIDATION_FAILED);
        }

        Account account = accounts.findByEmailAndDeletedAtIsNull(normalized).orElse(null);

        // 1. 账户维度：只有「这个账户栽过」才要图形码。查不到账户就不存在计数，
        //    因此未注册邮箱永远走不到这条分支，也就无法从它反推「这个邮箱注册过」。
        if (account != null && throttle.requiresCaptcha(account.id()) && !captchaPassed(attempt)) {
            throw new BizException(AuthErrorEnum.CAPTCHA_INVALID);
        }

        // 2. 凭证：查不到与密码不对同体。失败只计入限流计数，不改账户状态。
        boolean credentialsValid;
        if (account == null) {
            // 查不到账户也付一次 bcrypt 的成本（D3 实现约束）。返回值刻意丢弃：
            // 这次比对只为了耗时，结论永远是「凭证不对」。
            hasher.matches(attempt.rawPassword(), timingEqualizationHash);
            credentialsValid = false;
        } else {
            credentialsValid = account.passwordMatches(hasher, attempt.rawPassword());
        }
        if (!credentialsValid) {
            if (account == null) {
                throttle.recordSourceFailure(attempt.sourceKey());
            } else {
                throttle.recordFailure(account.id(), attempt.sourceKey());
            }
            throw new BizException(AuthErrorEnum.UNAUTHENTICATED);
        }

        // 3. 状态分派。密码已经对了，这里的 403 不再是枚举信道。
        return switch (account.status()) {
            case ACTIVE -> activeSession(account, now);
            case EMAIL_UNVERIFIED -> throw new BizException(AuthErrorEnum.EMAIL_UNVERIFIED);
            // 查询口径不含已删除行，这个分支按当前实现不可达；保留它是为了让
            // 「响应形态由状态唯一决定」在这里读起来是完整的——日后谁放宽查询口径，
            // 也不会顺带漏掉「已注销仍是 401」这条语义。
            case DELETED -> throw new BizException(AuthErrorEnum.UNAUTHENTICATED);
        };
    }

    /**
     * 图形码只在被要求时才校验，校验即消费。
     *
     * 缺失与答错归为同一个结果：若分开告知，客户端就能把「这张码对不对」
     * 与「这个账户要不要码」当成两个可探测的信号。
     */
    private boolean captchaPassed(LoginAttempt attempt) {
        if (attempt.captchaId() == null || attempt.captchaId().isBlank()
                || attempt.captchaAnswer() == null || attempt.captchaAnswer().isBlank()) {
            return false;
        }
        return captcha.verify(attempt.captchaId(), attempt.captchaAnswer());
    }

    private LoginResult activeSession(Account account, Instant now) {
        IssuedToken token = issuer.issue(account, now);
        sessions.save(AuthSession.open(account.id(), token.jti(), token.expiresAt()));
        // 成功登录才清零账户维度；来源维度不清，否则攻击者用自己的账户
        // 成功登录一次就能重置来源额度。
        throttle.clearAccountFailures(account.id());
        return new LoginResult(account.publicId(), account.email(), account.displayName(), token);
    }
}
