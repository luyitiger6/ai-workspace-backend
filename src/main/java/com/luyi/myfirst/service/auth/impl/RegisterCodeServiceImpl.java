package com.luyi.myfirst.service.auth.impl;

import com.luyi.myfirst.common.exception.AuthErrorEnum;
import com.luyi.myfirst.common.exception.BizException;
import com.luyi.myfirst.config.AuthProperties;
import com.luyi.myfirst.dao.auth.repository.AccountRepository;
import com.luyi.myfirst.service.auth.CaptchaService;
import com.luyi.myfirst.service.auth.RegisterCodeService;
import com.luyi.myfirst.service.auth.support.CodeHasher;
import com.luyi.myfirst.service.auth.support.CodePurpose;
import com.luyi.myfirst.service.auth.support.EmailNormalizer;
import com.luyi.myfirst.service.auth.support.EmailQuotaGuard;
import com.luyi.myfirst.service.auth.support.VerificationCodeSender;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;

/**
 * 注册码的换发与比对（spec「注册验证码链路」的两步）。
 *
 * 发码顺序是有讲究的：先消费图形码，再发码。反过来的话，一个错误的图形码也能触发投递，
 * 图形码就退化成「随便填个东西」的装饰。
 */
@Service
public class RegisterCodeServiceImpl implements RegisterCodeService {

    private static final String KEY_PREFIX = "wc:code:register:";
    private static final String COOLDOWN_PREFIX = "wc:cooldown:register:";
    private static final String FAIL_KEY_SUFFIX = ":fail";
    private static final String EMAIL_PURPOSE = "email";
    private static final String CODE_PURPOSE = "register-code";
    private static final int CODE_BOUND = 1_000_000;

    /**
     * 比对与删除必须在一次往返里完成。
     *
     * 返回三态：-1 键不存在（已过期、已用过、从未发过），0 存在但不匹配，1 匹配且已删除。
     * 分两步做（GET 再 DEL）的话，两个并发提交能拿到同一个「匹配」，而「一次性」正是码唯一的约束。
     */
    private static final DefaultRedisScript<Long> CONSUME_IF_MATCH = new DefaultRedisScript<>(
            "local stored = redis.call('GET', KEYS[1]) "
                    + "if not stored then return -1 end "
                    + "if stored == ARGV[1] then "
                    + "redis.call('DEL', KEYS[1], KEYS[2]) "
                    + "return 1 "
                    + "end "
                    + "return 0",
            Long.class);

    /**
     * 失败计数与 TTL 一次写入：分两步时进程死在中间会让计数键永不过期，
     * 而这个键没有业务 TTL 语义，它只是跟着码一起老去。
     */
    private static final DefaultRedisScript<Long> INCR_WITH_TTL = new DefaultRedisScript<>(
            "local n = redis.call('INCR', KEYS[1]) "
                    + "if n == 1 then redis.call('PEXPIRE', KEYS[1], ARGV[1]) end "
                    + "return n",
            Long.class);

    private final StringRedisTemplate redis;
    private final CodeHasher hasher;
    private final CaptchaService captcha;
    private final VerificationCodeSender sender;
    private final EmailQuotaGuard quota;
    private final AccountRepository accounts;
    private final Duration ttl;
    private final Duration resendCooldown;
    private final int maxVerifyAttempts;

    public RegisterCodeServiceImpl(StringRedisTemplate redis, CodeHasher hasher, CaptchaService captcha,
                                   VerificationCodeSender sender, EmailQuotaGuard quota,
                                   AccountRepository accounts, AuthProperties properties) {
        this.redis = redis;
        this.hasher = hasher;
        this.captcha = captcha;
        this.sender = sender;
        this.quota = quota;
        this.accounts = accounts;
        this.ttl = properties.emailCode().ttl();
        this.resendCooldown = properties.emailCode().resendCooldown();
        this.maxVerifyAttempts = properties.emailCode().maxVerifyAttempts();
    }

    /**
     * 换发注册码：消费图形码 → 生成 6 位数字 → 摘要入缓存 → 投递。
     *
     * 缓存键由邮箱摘要构成，值是码的摘要。绑定邮箱不靠额外的字段，靠「键就是那个邮箱」——
     * A 的码只能从 A 的键里查到，拿去提交 B 必然落空。
     */
    @Override
    public void issue(String email, String captchaId, String captchaAnswer) {
        String normalized = EmailNormalizer.normalize(email);
        if (normalized == null || normalized.isBlank()) {
            throw new BizException(AuthErrorEnum.VALIDATION_FAILED);
        }

        // 冷却先查、后设：先设的话，一个填错的图形码也会把用户锁在冷却里 60 秒。
        // 而只在图形码通过后才落冷却键，又会让「白烧一张图形码」成为探测冷却状态的门票。
        String cooldownKey = cooldownKeyOf(normalized);
        if (Boolean.TRUE.equals(redis.hasKey(cooldownKey))) {
            throw new BizException(AuthErrorEnum.RATE_LIMITED, remainingSeconds(cooldownKey));
        }

        if (!captcha.verify(captchaId, captchaAnswer)) {
            throw new BizException(AuthErrorEnum.CAPTCHA_INVALID);
        }

        // 配额在图形码之后：spec 要求「图形码错误或冷却命中的请求 MUST NOT 消耗配额」，
        // 也就意味着只有通过这两道校验的请求才计入。代价是配额耗尽时图形码已被烧掉，
        // 但把配额提到图形码之前，就等于允许任何人用任意答案探测「这个邮箱今天发了几封」。
        if (!quota.tryAcquire(normalized)) {
            throw new BizException(AuthErrorEnum.RATE_LIMITED, quota.retryAfterSeconds());
        }

        // 两次请求同时走到这里时，只让一个落下冷却键；否则并发重发能绕开冷却。
        if (!Boolean.TRUE.equals(redis.opsForValue().setIfAbsent(cooldownKey, "1", resendCooldown))) {
            throw new BizException(AuthErrorEnum.RATE_LIMITED, remainingSeconds(cooldownKey));
        }

        // 已注册邮箱：冷却与配额照常消耗，但不落码、不投递（spec「已注册邮箱请求注册码」）。
        // 这个分支必须排在冷却键落下之后：两条路径的消耗完全相同，探测者才拿不到
        // 「注册过的邮箱可以多试几次」这种额外信息。
        if (accounts.findByEmailAndDeletedAtIsNull(normalized).isPresent()) {
            return;
        }

        String code = sixDigitCode();
        redis.opsForValue().set(keyOf(normalized), hasher.hash(CODE_PURPOSE, code), ttl);
        redis.delete(failKeyOf(normalized));
        sender.send(normalized, code, CodePurpose.REGISTER);
    }

    /**
     * 比对注册码：匹配即删除，不匹配记一次失败，失败达上限则连同码一起作废。
     *
     * 三种失败（未发过/已过期/已验证过）在缓存看来是同一件事——键不在了。
     * 对外也只能是同一个错误码，区分它们就是告诉攻击者「这个邮箱刚刚注册过」。
     */
    @Override
    public void verify(String email, String code) {
        String normalized = EmailNormalizer.normalize(email);
        if (normalized == null || normalized.isBlank() || code == null || code.isBlank()) {
            throw new BizException(AuthErrorEnum.INVALID_CODE);
        }

        String key = keyOf(normalized);
        String failKey = failKeyOf(normalized);
        String candidate = hasher.hash(CODE_PURPOSE, code.trim());

        Long outcome = redis.execute(CONSUME_IF_MATCH, List.of(key, failKey), candidate);
        if (outcome != null && outcome == 1L) {
            return;
        }
        if (outcome != null && outcome == 0L) {
            long failures = increment(failKey, ttl);
            if (failures >= maxVerifyAttempts) {
                redis.delete(List.of(key, failKey));
            }
        }
        throw new BizException(AuthErrorEnum.INVALID_CODE);
    }

    /**
     * 缓存键：只含邮箱摘要，不含明文。
     *
     * 不在接口上暴露：键的内部构成是缓存细节，对外只该存在「发码 / 验码」两件事。
     * 测试与排障按 {@link RegisterCodeServiceImpl} 具体类型注入后使用。
     */
    public String keyOf(String normalizedEmail) {
        return KEY_PREFIX + hasher.hash(EMAIL_PURPOSE, normalizedEmail);
    }

    /** 失败计数的键。测试要清理它，排查现场时也靠它区分「码还在但已被试满」。 */
    public String failKeyOf(String normalizedEmail) {
        return keyOf(normalizedEmail) + FAIL_KEY_SUFFIX;
    }

    /** 重发冷却键。测试夹具需要绕过它才能造出「连着发两次码」的场景。 */
    public String cooldownKeyOf(String normalizedEmail) {
        return COOLDOWN_PREFIX + hasher.hash(EMAIL_PURPOSE, normalizedEmail);
    }

    /** 向上取整：Retry-After 是「还要等几秒」，向下取整会让客户端踩在秒边界上提前到达。 */
    private long remainingSeconds(String key) {
        Long millis = redis.getExpire(key, TimeUnit.MILLISECONDS);
        if (millis == null || millis <= 0) {
            return 1L;
        }
        return Math.max(1L, (millis + 999) / 1000);
    }

    /** 补零到 6 位：前导零是码的一部分，用整型存会让 001234 和 1234 变成两个不同的码。 */
    private static String sixDigitCode() {
        return String.format(Locale.ROOT, "%06d", ThreadLocalRandom.current().nextInt(CODE_BOUND));
    }

    private long increment(String key, Duration window) {
        Long value = redis.execute(INCR_WITH_TTL, List.of(key), String.valueOf(window.toMillis()));
        return value == null ? 0L : value;
    }
}
