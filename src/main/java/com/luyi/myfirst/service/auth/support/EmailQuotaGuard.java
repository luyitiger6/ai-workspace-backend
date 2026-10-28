package com.luyi.myfirst.service.auth.support;

import com.luyi.myfirst.config.AuthProperties;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Duration;
import java.time.LocalDate;
import java.util.List;

/**
 * 邮箱发码日配额（spec「邮箱发码日配额」）：同一邮箱每自然日最多 N 封，注册码与重置码合并计数。
 *
 * 键 = 前缀 + 邮箱摘要 + 日期。用摘要而不是明文，是因为缓存键会出现在运维工具、慢查询日志
 * 与 `KEYS` 的输出里，邮箱是个人信息。
 *
 * 日期进键而不是做滚动 24 小时窗口：「每天 N 次」若实现成滚动窗口，用户永远算不出要等到
 * 什么时候；自然日边界才能让 `Retry-After` 给出一个确定时刻。
 *
 * 比对与自增在同一个脚本里：分两步时两个并发请求能同时看到「还没满」，于是双双放行。
 * 超限时不增加计数，否则计数会一直涨到没人看得懂的数值。
 *
 * 注册码与重置码共用同一个键前缀，因此两类码天然合并计数——不需要额外的「用途」维度，
 * 那只会让配额在两类码之间此消彼长。
 */
@Component
public class EmailQuotaGuard {

    private static final String KEY_PREFIX = "wc:quota:email:";
    private static final String PURPOSE = "quota-email";

    private static final DefaultRedisScript<Long> ACQUIRE = new DefaultRedisScript<>(
            "local current = tonumber(redis.call('GET', KEYS[1]) or '0') "
                    + "if current >= tonumber(ARGV[2]) then return -1 end "
                    + "local n = redis.call('INCR', KEYS[1]) "
                    + "if n == 1 then redis.call('PEXPIRE', KEYS[1], ARGV[1]) end "
                    + "return n",
            Long.class);

    private final StringRedisTemplate redis;
    private final CodeHasher hasher;
    private final int dailyQuota;
    private final Clock clock;

    @Autowired
    public EmailQuotaGuard(StringRedisTemplate redis, CodeHasher hasher, AuthProperties properties) {
        this(redis, hasher, properties, Clock.systemDefaultZone());
    }

    /** 指定时钟：跨日与 `Retry-After` 的断言需要一个可拨动的「现在」。 */
    public EmailQuotaGuard(StringRedisTemplate redis, CodeHasher hasher, AuthProperties properties, Clock clock) {
        this.redis = redis;
        this.hasher = hasher;
        this.dailyQuota = properties.emailCode().dailyQuota();
        this.clock = clock;
    }

    /**
     * 占用一次投递额度。返回 false 表示今天已用完——调用方必须据此拒绝，
     * 而不是「先发再说」。
     */
    public boolean tryAcquire(String normalizedEmail) {
        Long result = redis.execute(ACQUIRE, List.of(keyOf(normalizedEmail)),
                String.valueOf(millisUntilNextMidnight()), String.valueOf(dailyQuota));
        return result != null && result > 0;
    }

    /** 到次日零点的秒数，用作 429 的 `Retry-After`。向上取整：向下取整会让客户端早到一秒。 */
    public long retryAfterSeconds() {
        return Math.max(1L, (millisUntilNextMidnight() + 999) / 1000);
    }

    /** 配额键。测试要清理它，也用它断言「键里没有明文邮箱」——移包后只能公开。 */
    public String keyOf(String normalizedEmail) {
        return KEY_PREFIX + hasher.hash(PURPOSE, normalizedEmail) + ":" + LocalDate.now(clock);
    }

    private long millisUntilNextMidnight() {
        long millis = Duration.between(clock.instant(),
                LocalDate.now(clock).plusDays(1).atStartOfDay(clock.getZone()).toInstant()).toMillis();
        return Math.max(1L, millis);
    }
}
