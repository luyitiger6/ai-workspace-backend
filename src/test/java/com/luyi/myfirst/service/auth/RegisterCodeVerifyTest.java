package com.luyi.myfirst.service.auth;

import com.luyi.myfirst.service.auth.impl.CaptchaServiceImpl;
import com.luyi.myfirst.service.auth.impl.RegisterCodeServiceImpl;
import com.luyi.myfirst.service.auth.model.CaptchaIssue;
import com.luyi.myfirst.service.auth.support.EmailNormalizer;
import com.luyi.myfirst.service.auth.support.EmailQuotaGuard;
import com.luyi.myfirst.service.auth.support.OutboxVerificationCodeSender;

import com.luyi.myfirst.common.exception.BizException;
import com.luyi.myfirst.config.AuthProperties;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 注册码比对的契约（tasks 4.3）：匹配即删、不匹配计数、达上限整条作废。
 *
 * 这里不走 HTTP，直接调 service：4.3 验的是比对本身。「不创建账户」那半句要等 5.1 的注册端点，
 * 届时在那边补端到端断言；此处能证到的是「比对不通过且缓存键已被抹掉」——账户还没被创建的前提。
 *
 * 明文码只能从 outbox 拿（D8：没有任何 HTTP 端点能读它）。
 */
@SpringBootTest
class RegisterCodeVerifyTest {

    private static final String EMAIL = "frank@example.com";
    private static final String CAPTCHA_ANSWER = "ABCD";
    private static final String CAPTCHA_KEY_PREFIX = "wc:captcha:";

    @Autowired
    private RegisterCodeServiceImpl registerCodes;

    @Autowired
    private CaptchaServiceImpl captcha;

    @Autowired
    private OutboxVerificationCodeSender outbox;

    @Autowired
    private EmailQuotaGuard quota;

    @Autowired
    private StringRedisTemplate redis;

    @Autowired
    private AuthProperties properties;

    private final List<String> keys = new ArrayList<>();

    @BeforeEach
    void resetOutbox() {
        outbox.clear();
    }

    @AfterEach
    void cleanUp() {
        if (!keys.isEmpty()) {
            redis.delete(keys);
            keys.clear();
        }
    }

    @Test
    @DisplayName("码对：通过，此后同一码立即失效")
    void acceptsMatchingCodeOnce() {
        String code = issueCode();

        assertThatCode(() -> registerCodes.verify(EMAIL, code)).doesNotThrowAnyException();

        assertRejected(EMAIL, code);
        assertThat(redis.hasKey(keyOf(EMAIL))).isFalse();
    }

    @Test
    @DisplayName("成功即删：调用返回时键已不在，而不是等 TTL 到期")
    void deletesKeyInSameCallWithoutWaitingForTtl() {
        String code = issueCode();
        String key = keyOf(EMAIL);

        assertThat(ttlOf(key)).isGreaterThan(Duration.ofSeconds(1));

        assertThatCode(() -> registerCodes.verify(EMAIL, code)).doesNotThrowAnyException();

        assertThat(redis.hasKey(key)).isFalse();
    }

    @Test
    @DisplayName("重复提交同一码：拒绝，且不记失败尝试——手抖重发不该白白消耗一次机会")
    void repeatedSubmissionIsRejectedWithoutCountingAsFailure() {
        String code = issueCode();

        assertThatCode(() -> registerCodes.verify(EMAIL, code)).doesNotThrowAnyException();

        assertRejected(EMAIL, code);
        assertThat(redis.hasKey(failKeyOf(EMAIL))).isFalse();
    }

    @Test
    @DisplayName("码超过存活时间：拒绝，且不留下失败计数")
    void rejectsExpiredCode() {
        String code = issueCode();
        String key = keyOf(EMAIL);

        redis.expire(key, Duration.ofMillis(1));
        awaitGone(key);

        assertRejected(EMAIL, code);
        assertThat(redis.hasKey(failKeyOf(EMAIL))).isFalse();
    }

    @Test
    @DisplayName("码错一次：拒绝但码还留着——一次手滑不该让用户重走图形码流程")
    void rejectsWrongCodeButKeepsTheCode() {
        String code = issueCode();

        assertRejected(EMAIL, wrong(code));

        assertThat(redis.hasKey(keyOf(EMAIL))).isTrue();
        assertThatCode(() -> registerCodes.verify(EMAIL, code)).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("失败达上限：码与计数一起作废，连正确码也进不去")
    void burnsCodeAfterMaxFailures() {
        String code = issueCode();
        String wrong = wrong(code);

        for (int i = 0; i < properties.emailCode().maxVerifyAttempts(); i++) {
            assertRejected(EMAIL, wrong);
        }

        assertThat(redis.hasKey(keyOf(EMAIL))).isFalse();
        assertThat(redis.hasKey(failKeyOf(EMAIL))).isFalse();
        assertRejected(EMAIL, code);
    }

    @Test
    @DisplayName("重新发码清零失败计数：上一次的失败不拖累新码")
    void reissuingResetsFailures() {
        String first = issueCode();
        for (int i = 0; i < properties.emailCode().maxVerifyAttempts() - 1; i++) {
            assertRejected(EMAIL, wrong(first));
        }

        String fresh = issueCode();

        assertThat(redis.hasKey(failKeyOf(EMAIL))).isFalse();
        assertThatCode(() -> registerCodes.verify(EMAIL, fresh)).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("没发过码的邮箱：直接拒绝，不产生失败计数")
    void rejectsUnknownEmail() {
        String unknown = "nobody@example.com";

        assertRejected(unknown, "123456");

        assertThat(redis.hasKey(failKeyOf(unknown))).isFalse();
    }

    @Test
    @DisplayName("空邮箱或空码：直接拒绝，且不消耗仍然有效的码")
    void rejectsBlankInput() {
        String code = issueCode();

        assertRejected("   ", "123456");
        assertRejected(EMAIL, "   ");
        assertRejected(EMAIL, null);

        assertThatCode(() -> registerCodes.verify(EMAIL, code)).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("邮箱大小写与首尾空白不影响比对")
    void normalizesEmailOnVerify() {
        String code = issueCode();

        assertThatCode(() -> registerCodes.verify("  Frank@Example.COM ", code))
                .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("码带首尾空白：照常接受——从邮件里复制常把换行也带进来")
    void toleratesSurroundingWhitespace() {
        String code = issueCode();

        assertThatCode(() -> registerCodes.verify(EMAIL, "  " + code + "\n"))
                .doesNotThrowAnyException();
    }

    private String issueCode() {
        // 冷却与日配额不是本类的被测对象：每次发码前先拆掉，否则第二个码永远发不出去。
        String normalized = EmailNormalizer.normalize(EMAIL);
        redis.delete(List.of(registerCodes.cooldownKeyOf(normalized), quota.keyOf(normalized)));
        CaptchaIssue issue = captcha.issue(CAPTCHA_ANSWER);
        keys.add(CAPTCHA_KEY_PREFIX + issue.captchaId());
        registerCodes.issue(EMAIL, issue.captchaId(), CAPTCHA_ANSWER);
        keyOf(EMAIL);
        List<OutboxVerificationCodeSender.Message> messages = outbox.messages();
        return messages.get(messages.size() - 1).code();
    }

    private String keyOf(String email) {
        String normalized = EmailNormalizer.normalize(email);
        String key = registerCodes.keyOf(normalized);
        keys.add(key);
        keys.add(registerCodes.failKeyOf(normalized));
        keys.add(registerCodes.cooldownKeyOf(normalized));
        keys.add(quota.keyOf(normalized));
        return key;
    }

    private String failKeyOf(String email) {
        String key = registerCodes.failKeyOf(EmailNormalizer.normalize(email));
        keys.add(key);
        return key;
    }

    private void assertRejected(String email, String code) {
        assertThatThrownBy(() -> registerCodes.verify(email, code))
                .isInstanceOf(BizException.class)
                .extracting(e -> ((BizException) e).getErrorCode())
                .isEqualTo("INVALID_CODE");
    }

    /** 六位数字里换一个不同的值：比构造随机错码更稳定，也不会撞上正确码。 */
    private static String wrong(String code) {
        return code.equals("000000") ? "000001" : "000000";
    }

    /** 剩余存活时间：用来证明「删键」不是「过期」，两者在测试里看起来都是 hasKey=false。 */
    private Duration ttlOf(String key) {
        Long millis = redis.getExpire(key, TimeUnit.MILLISECONDS);
        return millis == null || millis < 0 ? Duration.ZERO : Duration.ofMillis(millis);
    }

    private void awaitGone(String key) {
        long deadline = System.currentTimeMillis() + 2000;
        while (redis.hasKey(key) && System.currentTimeMillis() < deadline) {
            try {
                Thread.sleep(20);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException(e);
            }
        }
        assertThat(redis.hasKey(key)).isFalse();
    }
}
