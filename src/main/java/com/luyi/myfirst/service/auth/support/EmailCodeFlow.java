package com.luyi.myfirst.service.auth.support;

import com.luyi.myfirst.common.exception.AuthErrorEnum;
import com.luyi.myfirst.common.exception.BizException;
import com.luyi.myfirst.config.AuthProperties;
import com.luyi.myfirst.dao.auth.repository.AccountRepository;
import com.luyi.myfirst.service.auth.CaptchaService;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;

/**
 * 邮箱验证码链路：图形码 → 冷却 → 日配额 → 生成 6 位码 → 摘要入缓存 → 投递；以及反向的比对。
 *
 * 注册码与重置码共用这一段（D6）：两者的密码学与限流要求完全相同，只有「发给谁」和
 * 「验过之后做什么」不同。后者的决定权在调用方，前者就是下面 {@link #shouldDeliver} 那一个判断。
 *
 * 发码顺序是有讲究的：先消费图形码，再发码。反过来的话，一个错误的图形码也能触发投递，
 * 图形码就退化成「随便填个东西」的装饰。
 *
 * 缓存键按用途分段（{@code wc:code:register:} / {@code wc:code:reset:}），
 * 即使两类码由同一段代码生成，一个用途的码也挪不到另一个端点上使用。
 */
@Component
public class EmailCodeFlow {

    private static final String KEY_PREFIX = "wc:code:";

    /**
     * 重发冷却跨用途共用：spec 的冷却约束写在「同一邮箱」而不是「同一邮箱的同一类码」上。
     * 按用途各记一份的话，连发注册码与重置码就能在 60 秒内寄出两封，冷却名存实亡。
     */
    private static final String COOLDOWN_PREFIX = "wc:cooldown:email:";

    private static final String FAIL_KEY_SUFFIX = ":fail";
    private static final String EMAIL_PURPOSE = "email";
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

    public EmailCodeFlow(StringRedisTemplate redis, CodeHasher hasher, CaptchaService captcha,
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
     * 换发一枚码：消费图形码 → 占用冷却与配额 → 生成 → 摘要入缓存 → 投递。
     *
     * 缓存键由邮箱摘要构成，值是码的摘要。绑定邮箱不靠额外的字段，靠「键就是那个邮箱」——
     * A 的码只能从 A 的键里查到，拿去提交 B 必然落空。
     */
    public void issue(String email, CodePurpose purpose, String captchaId, String captchaAnswer) {
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

        // 投递判定必须排在冷却键落下之后：两条路径的消耗完全相同，探测者才拿不到
        // 「注册过的邮箱可以多试几次」这种额外信息。
        if (!shouldDeliver(normalized, purpose)) {
            return;
        }

        String code = sixDigitCode();
        redis.opsForValue().set(keyOf(normalized, purpose), hasher.hash(hashPurpose(purpose), code), ttl);
        redis.delete(failKeyOf(normalized, purpose));
        sender.send(normalized, code, purpose);
    }

    /**
     * 比对一枚码：匹配即删除，不匹配记一次失败，失败达上限则连同码一起作废。
     *
     * 三种失败（未发过/已过期/已验证过）在缓存看来是同一件事——键不在了。
     * 对外也只能是同一个错误码，区分它们就是告诉攻击者「这个邮箱刚刚走完过这条链路」。
     */
    public void verify(String email, String code, CodePurpose purpose) {
        String normalized = EmailNormalizer.normalize(email);
        if (normalized == null || normalized.isBlank() || code == null || code.isBlank()) {
            throw new BizException(AuthErrorEnum.INVALID_CODE);
        }

        String key = keyOf(normalized, purpose);
        String failKey = failKeyOf(normalized, purpose);
        String candidate = hasher.hash(hashPurpose(purpose), code.trim());

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
     * 这枚码该不该真的寄出去。
     *
     * 两条链路的判断互为镜像：注册码寄给「还没有 active 账户」的邮箱，重置码寄给
     * 「已经有 active 账户」的邮箱。两者都返回与正常情形一致的响应——不投递只是
     * 内部动作，不能变成对外可观察的差别，否则这两个端点就都成了邮箱存在性预言机。
     *
     * 查重条件用 {@code deletedAt} 而不是 {@code status}，与唯一索引的生成列同口径：
     * 已注销的邮箱对注册链路是「可注册」，对重置链路是「没有账户」。
     */
    private boolean shouldDeliver(String normalizedEmail, CodePurpose purpose) {
        boolean active = accounts.findByEmailAndDeletedAtIsNull(normalizedEmail).isPresent();
        return purpose == CodePurpose.REGISTER ? !active : active;
    }

    /** 缓存键：按用途分段，只含邮箱摘要，不含明文。 */
    public String keyOf(String normalizedEmail, CodePurpose purpose) {
        return KEY_PREFIX + purpose.key() + ":" + hasher.hash(EMAIL_PURPOSE, normalizedEmail);
    }

    /** 失败计数的键。测试要清理它，排查现场时也靠它区分「码还在但已被试满」。 */
    public String failKeyOf(String normalizedEmail, CodePurpose purpose) {
        return keyOf(normalizedEmail, purpose) + FAIL_KEY_SUFFIX;
    }

    /** 重发冷却键。跨用途共用，见 {@link #COOLDOWN_PREFIX} 的说明。 */
    public String cooldownKeyOf(String normalizedEmail) {
        return COOLDOWN_PREFIX + hasher.hash(EMAIL_PURPOSE, normalizedEmail);
    }

    /** 摘要的用途前缀与缓存键的用途段同源，避免两处各写一份字面量后悄悄分叉。 */
    private static String hashPurpose(CodePurpose purpose) {
        return purpose.key() + "-code";
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
