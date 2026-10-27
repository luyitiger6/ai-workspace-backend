package com.luyi.myfirst.service.auth;

import com.luyi.myfirst.config.AuthProperties;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.List;

/**
 * 登录限流：账户维度连续失败后要求图形码，来源维度超阈直接拒绝（D3）。
 *
 * 两类计数都只活在缓存里，不参与任何状态判定——缓存丢失只意味着限流暂时放宽，
 * 不会把账户变成「不可登录」，账户状态因此永远不受登录失败影响（spec Req 4）。
 *
 * 本类不做来源识别：传入的 sourceKey 已由调用方按 5.9 的规则规范化
 * （可信代理给出的地址、未知来源单桶、或 IPv6 的 /64 前缀）。
 */
@Component
public class LoginThrottle {

    private static final String ACCOUNT_KEY_PREFIX = "wc:login:fail:acct:";
    private static final String SOURCE_KEY_PREFIX = "wc:login:fail:src:";

    /**
     * 账户计数不看时间窗口：它的语义是「自上次成功登录以来的连续失败」，
     * 由成功登录清零。这里的 TTL 只为防止键无界堆积，取值足够长以免改变语义。
     */
    private static final Duration ACCOUNT_FAILURE_TTL = Duration.ofDays(30);

    /**
     * INCR 与 PEXPIRE 必须在一次往返内完成：分两步执行时若进程死在中间，
     * 来源键会永久保留，该来源将变成「一次超限即永久拒绝」。
     */
    private static final DefaultRedisScript<Long> INCR_WITH_TTL = new DefaultRedisScript<>(
            "local n = redis.call('INCR', KEYS[1]) "
                    + "if n == 1 then redis.call('PEXPIRE', KEYS[1], ARGV[1]) end "
                    + "return n",
            Long.class);

    private final StringRedisTemplate redis;
    private final AuthProperties.Login login;

    public LoginThrottle(StringRedisTemplate redis, AuthProperties properties) {
        this.redis = redis;
        this.login = properties.login();
    }

    /** 账户连续失败已达阈值：后续登录必须先通过图形码，才轮到密码比对。 */
    public boolean requiresCaptcha(long accountId) {
        return count(ACCOUNT_KEY_PREFIX + accountId) >= login.failThreshold();
    }

    /** 来源在窗口内的失败已达上限：直接 429，不比对凭证。 */
    public boolean isSourceBlocked(String sourceKey) {
        return count(SOURCE_KEY_PREFIX + sourceKey) >= login.sourceMax();
    }

    /** 429 的 Retry-After，来源维度取固定值而非递增：NAT 后的合法用户不应被指数惩罚。 */
    public Duration sourceRetryAfter() {
        return login.retryAfter();
    }

    /**
     * 记一次凭证校验失败：账户与来源两个维度同时累计。
     * 来源计数不区分账户，否则「每个账户只错一次」的撞库就免于限流。
     */
    public void recordFailure(long accountId, String sourceKey) {
        increment(ACCOUNT_KEY_PREFIX + accountId, ACCOUNT_FAILURE_TTL);
        recordSourceFailure(sourceKey);
    }

    /**
     * 只记来源维度：邮箱不存在时没有账户可记，但这次失败仍须占用来源额度，
     * 否则未知邮箱可以无限试探（spec「单来源撞多账户共用额度」）。
     */
    public void recordSourceFailure(String sourceKey) {
        increment(SOURCE_KEY_PREFIX + sourceKey, login.sourceWindow());
    }

    /**
     * 成功登录清零账户维度。
     * 来源维度刻意不清零：否则攻击者用自己的账户成功登录一次就能重置来源额度。
     */
    public void clearAccountFailures(long accountId) {
        redis.delete(ACCOUNT_KEY_PREFIX + accountId);
    }

    private long count(String key) {
        String value = redis.opsForValue().get(key);
        return value == null ? 0L : Long.parseLong(value);
    }

    private void increment(String key, Duration ttl) {
        redis.execute(INCR_WITH_TTL, List.of(key), String.valueOf(ttl.toMillis()));
    }
}
