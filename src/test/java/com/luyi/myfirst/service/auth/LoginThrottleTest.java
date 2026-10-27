package com.luyi.myfirst.service.auth;

import com.luyi.myfirst.config.AuthProperties;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 限流计数的行为验证：阈值取小值（2 / 3），否则没人会去触发这两条分支（D3）。
 *
 * 计数写在真实 Redis 上——它本来就是跨实例共享的东西，换成内存实现只能测出假绿灯。
 * 每个用例用随机账户与随机来源键，结束后删除，不污染共用 db 3。
 */
@SpringBootTest
class LoginThrottleTest {

    private static final int FAIL_THRESHOLD = 2;
    private static final int SOURCE_MAX = 3;

    @Autowired
    private StringRedisTemplate redis;

    private LoginThrottle throttle;
    private long accountId;
    private String sourceKey;

    @BeforeEach
    void setUp() {
        throttle = new LoginThrottle(redis, authProperties(FAIL_THRESHOLD, SOURCE_MAX));
        accountId = ThreadLocalRandom.current().nextLong(1_000_000L, Long.MAX_VALUE);
        sourceKey = "test-" + UUID.randomUUID();
    }

    @AfterEach
    void cleanUp() {
        redis.delete(List.of(
                "wc:login:fail:acct:" + accountId,
                "wc:login:fail:src:" + sourceKey));
    }

    @Test
    @DisplayName("连续失败达到阈值后要求图形码，未达阈值不要求")
    void requiresCaptchaOnceThresholdReached() {
        throttle.recordFailure(accountId, sourceKey);
        assertThat(throttle.requiresCaptcha(accountId)).isFalse();

        throttle.recordFailure(accountId, sourceKey);
        assertThat(throttle.requiresCaptcha(accountId)).isTrue();
    }

    @Test
    @DisplayName("成功登录清零账户计数：再次登录不再要求图形码")
    void successfulLoginClearsAccountFailures() {
        throttle.recordFailure(accountId, sourceKey);
        throttle.recordFailure(accountId, sourceKey);
        assertThat(throttle.requiresCaptcha(accountId)).isTrue();

        throttle.clearAccountFailures(accountId);
        assertThat(throttle.requiresCaptcha(accountId)).isFalse();
    }

    @Test
    @DisplayName("来源在窗口内失败达到上限即被拒，并给出 Retry-After")
    void sourceIsBlockedOnceLimitReached() {
        throttle.recordSourceFailure(sourceKey);
        throttle.recordSourceFailure(sourceKey);
        assertThat(throttle.isSourceBlocked(sourceKey)).isFalse();

        throttle.recordSourceFailure(sourceKey);
        assertThat(throttle.isSourceBlocked(sourceKey)).isTrue();
        assertThat(throttle.sourceRetryAfter()).isEqualTo(Duration.ofSeconds(60));
    }

    @Test
    @DisplayName("来源计数不区分账户：单来源撞多账户共用同一额度")
    void sourceQuotaIsSharedAcrossAccounts() {
        long anotherAccount = accountId + 1;
        try {
            throttle.recordFailure(accountId, sourceKey);
            throttle.recordFailure(anotherAccount, sourceKey);
            throttle.recordFailure(anotherAccount, sourceKey);

            assertThat(throttle.isSourceBlocked(sourceKey)).isTrue();
            assertThat(throttle.requiresCaptcha(accountId)).isFalse();
            assertThat(throttle.requiresCaptcha(anotherAccount)).isTrue();
        } finally {
            redis.delete("wc:login:fail:acct:" + anotherAccount);
        }
    }

    private static AuthProperties authProperties(int failThreshold, int sourceMax) {
        return new AuthProperties(
                Duration.ofDays(7),
                "test-only-secret",
                false,
                List.of("http://localhost:3000"),
                new AuthProperties.Captcha(Duration.ofMinutes(2)),
                new AuthProperties.EmailCode(Duration.ofMinutes(5), Duration.ofSeconds(60), 5, 5),
                new AuthProperties.Login(failThreshold, Duration.ofMinutes(10), sourceMax, Duration.ofSeconds(60)));
    }
}
