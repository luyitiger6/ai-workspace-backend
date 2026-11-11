package com.luyi.myfirst.web.auth.interceptor;

import com.luyi.myfirst.common.exception.BizException;
import com.luyi.myfirst.config.WriteRateLimitProperties;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.http.HttpStatus;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 写操作来源限流的五条边界（spec「全站写操作来源限流」）。
 *
 * 用真实 Redis 而不是打桩：计数全部落在 Lua 里，桩掉缓存等于把被测的那段逻辑也桩掉了。
 * 代价是计数会留在共享实例上，所以每个用例结束后按前缀清干净——数据库回滚救不了缓存。
 *
 * 已解析来源用 203.0.113.0/24（RFC 5737 文档网段，不会与真实流量撞车），
 * 未知来源用 10.0.0.5（对端不在 trusted-proxies 里）。
 */
@SpringBootTest
class WriteRateLimitInterceptorTest {

    private static final String TRUSTED_PEER = "127.0.0.1";
    private static final String UNTRUSTED_PEER = "10.0.0.5";
    private static final String CLIENT = "203.0.113.7";
    private static final String OTHER_CLIENT = "203.0.113.8";

    @Autowired
    private WriteRateLimitInterceptor interceptor;

    @Autowired
    private SourceKeyResolver sourceKeys;

    @Autowired
    private WriteRateLimitProperties limits;

    @Autowired
    private StringRedisTemplate redis;

    @AfterEach
    void clearCounters() {
        Set<String> keys = redis.keys(WriteRateLimitInterceptor.KEY_PREFIX + "*");
        if (keys != null && !keys.isEmpty()) {
            redis.delete(keys);
        }
    }

    @Test
    @DisplayName("来源超限：第 N+1 次得到 429 + RATE_LIMITED + Retry-After，写操作不再执行")
    void rejectsAfterQuotaExhausted() {
        for (int i = 0; i < limits.max(); i++) {
            assertThat(handle(write(CLIENT))).as("第 %d 次写请求应当放行", i + 1).isTrue();
        }

        assertThatThrownBy(() -> handle(write(CLIENT)))
                .isInstanceOfSatisfying(BizException.class, e -> {
                    assertThat(e.getErrorCode()).isEqualTo("RATE_LIMITED");
                    assertThat(e.getHttpStatus()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
                    // 剩余 TTL 向上取整、最少 1 秒：不可能超过窗口长度
                    assertThat(e.getRetryAfterSeconds())
                            .isBetween(1L, limits.window().toSeconds());
                });
    }

    @Test
    @DisplayName("读请求不占额度：反复 GET 之后写桶里仍是空的")
    void readRequestsDoNotConsumeQuota() {
        for (int i = 0; i < limits.max() + 5; i++) {
            assertThat(handle(new MockHttpServletRequest("GET", "/api/posts"))).isTrue();
            assertThat(handle(new MockHttpServletRequest("HEAD", "/api/posts/abc"))).isTrue();
            assertThat(handle(new MockHttpServletRequest("OPTIONS", "/api/posts"))).isTrue();
        }

        assertThat(redis.keys(WriteRateLimitInterceptor.KEY_PREFIX + "*")).isNullOrEmpty();
    }

    @Test
    @DisplayName("认证端点不消耗写额度：登录与发码反复请求都不进写桶")
    void authEndpointsAreExempt() {
        for (int i = 0; i < limits.max() + 5; i++) {
            assertThat(handle(post(CLIENT, "/api/auth"))).isTrue();
            assertThat(handle(post(CLIENT, "/api/auth/login"))).isTrue();
            assertThat(handle(post(CLIENT, "/api/auth/register-code"))).isTrue();
        }

        assertThat(redis.keys(WriteRateLimitInterceptor.KEY_PREFIX + "*")).isNullOrEmpty();
    }

    @Test
    @DisplayName("未知来源独立额度：先于已解析来源耗尽，且不挤占已解析来源的额度")
    void unknownBucketHasItsOwnQuota() {
        for (int i = 0; i < limits.unknownMax(); i++) {
            assertThat(handle(unknownWrite())).as("未知来源第 %d 次写请求应当放行", i + 1).isTrue();
        }

        assertThatThrownBy(() -> handle(unknownWrite()))
                .isInstanceOfSatisfying(BizException.class, e ->
                        assertThat(e.getErrorCode()).isEqualTo("RATE_LIMITED"));

        // 已解析来源此刻仍应全量可用：独立额度的意义就在这里
        for (int i = 0; i < limits.max(); i++) {
            assertThat(handle(write(CLIENT))).isTrue();
        }
        assertThat(handle(write(OTHER_CLIENT))).isTrue();
    }

    @Test
    @DisplayName("多实例共用同一额度：一个实例耗尽后，另一个实例读到同一份额度桶")
    void twoInterceptorsShareTheSameQuota() {
        // 生产里多个副本各自持有拦截器实例、共用同一个 Redis；这里用两个实例复现同一拓扑。
        WriteRateLimitInterceptor other = new WriteRateLimitInterceptor(redis, sourceKeys, limits);

        for (int i = 0; i < limits.max(); i++) {
            assertThat(other.preHandle(write(CLIENT), new MockHttpServletResponse(), new Object()))
                    .as("另一个实例第 %d 次写请求应当放行", i + 1).isTrue();
        }

        assertThatThrownBy(() -> handle(write(CLIENT)))
                .as("本实例一次未记数，仍应看到另一个实例写下的同一份额度")
                .isInstanceOfSatisfying(BizException.class, e ->
                        assertThat(e.getErrorCode()).isEqualTo("RATE_LIMITED"));
    }

    @Test
    @DisplayName("缓存不可用：按 fail-open 放行，而不是把一次 Redis 抖动放大成全站不可写")
    void failsOpenWhenCacheUnavailable() {
        StringRedisTemplate broken = new StringRedisTemplate() {
            @Override
            public <T> T execute(RedisScript<T> script, List<String> keys, Object... args) {
                throw new RedisConnectionFailureException("redis down");
            }
        };
        WriteRateLimitInterceptor failOpen = new WriteRateLimitInterceptor(broken, sourceKeys, limits);

        assertThat(failOpen.preHandle(write(CLIENT), new MockHttpServletResponse(), new Object())).isTrue();
    }

    private boolean handle(MockHttpServletRequest request) {
        return interceptor.preHandle(request, new MockHttpServletResponse(), new Object());
    }

    private static MockHttpServletRequest write(String client) {
        return post(client, "/api/posts");
    }

    /** 对端不在 trusted-proxies 里：转发头一律不看，请求直接落未知来源桶。 */
    private static MockHttpServletRequest unknownWrite() {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/posts");
        request.setRemoteAddr(UNTRUSTED_PEER);
        request.addHeader("X-Forwarded-For", CLIENT);
        return request;
    }

    private static MockHttpServletRequest post(String client, String path) {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", path);
        request.setRemoteAddr(TRUSTED_PEER);
        request.addHeader("X-Forwarded-For", client);
        return request;
    }
}
