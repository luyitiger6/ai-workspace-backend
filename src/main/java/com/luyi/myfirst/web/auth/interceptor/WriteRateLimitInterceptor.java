package com.luyi.myfirst.web.auth.interceptor;

import com.luyi.myfirst.common.exception.AuthErrorEnum;
import com.luyi.myfirst.common.exception.BizException;
import com.luyi.myfirst.config.WriteRateLimitProperties;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.TimeUnit;

/**
 * 全站写操作的来源限流（spec「全站写操作来源限流」，design D3~D6）。
 *
 * 只拦会改状态的方法，并且跳过认证域：登录、发码、找回密码已有各自维度的限流，
 * 再把它们算进写额度，只会让「输错一次验证码」和「发一条帖子」竞争同一份额度。
 *
 * 顺序是契约：注册在 {@link OriginGuard#ORDER} 之后、{@link AuthInterceptor#ORDER} 之前。
 * 在前者之后，跨站请求先得到 403，不消耗额度；在后者之前，未登录的匿名写请求也受限，
 * 而不是先撞 401、再被客户端无限重试。
 *
 * 计数复用 {@code LoginThrottle} 的同一套 Lua：INCR 与 PEXPIRE 必须原子，
 * 分两步执行时进程死在中间会让键永不过期，该来源就变成「一次超限即永久拒绝」。
 *
 * 缓存故障按 fail-open 处理（D6）：只捕获 {@link DataAccessException}，其余异常照常抛出。
 * 限流是防刷手段，不是业务正确性的前提；把一次 Redis 抖动放大成全站不可写并不划算。
 */
@Component
public class WriteRateLimitInterceptor implements HandlerInterceptor {

    /** 注册顺序：来源校验之后、凭证判定之前。 */
    public static final int ORDER = 5;

    /**
     * 计数键前缀。测试按它清理自造计数，与登录限流的键族互不重叠。
     *
     * 包级可见而非 private：测试与实现同包，清理时要按前缀扫，
     * 把字面量再抄一遍只会多出一个会漂移的真源。
     */
    static final String KEY_PREFIX = "wc:ratelimit:write:";

    /** 认证域的路径前缀：这些端点已有专门的限流，不再消耗写额度。 */
    private static final String AUTH_PREFIX = "/api/auth";

    private static final Set<String> SAFE_METHODS = Set.of("GET", "HEAD", "OPTIONS");

    private static final Logger log = LoggerFactory.getLogger(WriteRateLimitInterceptor.class);

    private static final DefaultRedisScript<Long> INCR_WITH_TTL = new DefaultRedisScript<>(
            "local n = redis.call('INCR', KEYS[1]) "
                    + "if n == 1 then redis.call('PEXPIRE', KEYS[1], ARGV[1]) end "
                    + "return n",
            Long.class);

    private final StringRedisTemplate redis;
    private final SourceKeyResolver sourceKeys;
    private final WriteRateLimitProperties limits;

    public WriteRateLimitInterceptor(StringRedisTemplate redis,
                                     SourceKeyResolver sourceKeys,
                                     WriteRateLimitProperties limits) {
        this.redis = redis;
        this.sourceKeys = sourceKeys;
        this.limits = limits;
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        if (skips(request)) {
            return true;
        }
        String sourceKey = sourceKeys.resolve(request);
        String key = keyOf(sourceKey);
        long quota = quotaOf(sourceKey);
        try {
            long used = increment(key);
            if (used > quota) {
                throw new BizException(AuthErrorEnum.RATE_LIMITED, retryAfterSeconds(key));
            }
        } catch (DataAccessException e) {
            // 记 WARN 而不是静默吞掉：告警依赖日志，这是已知取舍（D6）。
            log.warn("写操作来源限流计数不可用，按 fail-open 放行：method={}, path={}, source={}, reason={}",
                    request.getMethod(), request.getRequestURI(), sourceKey, e.getMessage());
        }
        return true;
    }

    /**
     * 键的构成只有这一处，调用方不该自己拼。
     *
     * 公开是为了让测试能精确清掉自己造的计数：计数活在共享 Redis 里，
     * 数据库回滚救不了它，残留会污染同一实例上的其他用例。
     */
    public String keyOf(String sourceKey) {
        return KEY_PREFIX + sourceKey;
    }

    /** 未知来源走独立额度：代理配置异常时，它不该和已解析来源竞争同一份额度。 */
    private long quotaOf(String sourceKey) {
        return TrustedProxySourceKeyResolver.UNKNOWN_SOURCE.equals(sourceKey)
                ? limits.unknownMax()
                : limits.max();
    }

    private long increment(String key) {
        Long used = redis.execute(INCR_WITH_TTL, List.of(key), String.valueOf(limits.window().toMillis()));
        return used == null ? 0L : used;
    }

    /**
     * Retry-After 取当前键的剩余 TTL（D5）：向上取整，最小 1 秒。
     *
     * 用剩余 TTL 而不是固定窗口长度——写请求的窗口短，固定值会让客户端多等近一个窗口。
     * 取不到 TTL（键恰好过期或异常）时兜底 1 秒：那是「一秒后就能重试」，
     * 而不是拿一个猜测值把拒绝时长放大。
     */
    private long retryAfterSeconds(String key) {
        Long ttlMillis = redis.getExpire(key, TimeUnit.MILLISECONDS);
        if (ttlMillis == null || ttlMillis <= 0) {
            return 1L;
        }
        return Math.max(1L, (ttlMillis + 999) / 1000);
    }

    private static boolean skips(HttpServletRequest request) {
        if (SAFE_METHODS.contains(request.getMethod().toUpperCase(Locale.ROOT))) {
            return true;
        }
        String path = requestPath(request);
        return path.equals(AUTH_PREFIX) || path.startsWith(AUTH_PREFIX + "/");
    }

    /** 去掉上下文路径后的请求路径，与 {@link AuthInterceptor} 同一取法。 */
    private static String requestPath(HttpServletRequest request) {
        String uri = request.getRequestURI();
        String contextPath = request.getContextPath();
        if (contextPath != null && !contextPath.isEmpty() && uri.startsWith(contextPath)) {
            return uri.substring(contextPath.length());
        }
        return uri;
    }
}
