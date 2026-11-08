package com.luyi.myfirst.web.auth.interceptor;

import org.springframework.data.redis.core.StringRedisTemplate;

import java.util.Set;

/**
 * 写限流计数键的测试侧清理。
 *
 * 计数键活在共享 Redis 上，数据库事务回滚救不了它：一个控制器测试类留下的额度，
 * 会让下一个类在毫无关系的地方收到 429。所有会发非 auth 写请求的控制器测试都在
 * 用例前后清一次，键前缀只从这里取——三份测试各自抄一遍字面量，迟早有一份漂移。
 *
 * 与拦截器同包，因此直接读包级可见的 {@link WriteRateLimitInterceptor#KEY_PREFIX}，
 * 不必为了测试把它提成 public。
 */
public final class WriteRateLimitBuckets {

    private WriteRateLimitBuckets() {
    }

    /** 清空全部写限流计数键；没有键时什么都不做。 */
    public static void clear(StringRedisTemplate redis) {
        Set<String> keys = redis.keys(WriteRateLimitInterceptor.KEY_PREFIX + "*");
        if (keys != null && !keys.isEmpty()) {
            redis.delete(keys);
        }
    }
}
