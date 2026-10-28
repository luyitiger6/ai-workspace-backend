package com.luyi.myfirst.service.auth;

import com.luyi.myfirst.service.auth.impl.CaptchaServiceImpl;
import com.luyi.myfirst.service.auth.impl.RegisterCodeServiceImpl;
import com.luyi.myfirst.service.auth.model.CaptchaIssue;
import com.luyi.myfirst.service.auth.support.CodeHasher;
import com.luyi.myfirst.service.auth.support.EmailNormalizer;
import com.luyi.myfirst.service.auth.support.EmailQuotaGuard;
import com.luyi.myfirst.service.auth.support.OutboxVerificationCodeSender;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.luyi.myfirst.common.exception.BizException;
import com.luyi.myfirst.config.AuthProperties;
import com.luyi.myfirst.web.auth.dto.RegisterCodeRequest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;

import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 邮箱发码日配额（tasks 4.9）：每邮箱每自然日 N 封，注册码与重置码合并计数。
 *
 * 「跨日重置」与「Retry-After 指向次日零点」需要一个可拨动的「现在」，所以那两条用
 * 固定 Clock 直接构造 guard；其余几条走真实装配，验的是配额在链路里的位置——
 * 图形码填错、冷却命中都不该记一次额度，这两条正是最容易实现错的地方。
 */
@SpringBootTest
@AutoConfigureMockMvc
class EmailQuotaTest {

    private static final String EMAIL = "mallory@example.com";
    private static final String ORIGIN = "http://localhost:3000";
    private static final String CAPTCHA_KEY_PREFIX = "wc:captcha:";
    private static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");

    @Autowired
    private RegisterCodeServiceImpl registerCodes;

    @Autowired
    private EmailQuotaGuard quota;

    @Autowired
    private CaptchaServiceImpl captcha;

    @Autowired
    private CodeHasher hasher;

    @Autowired
    private AuthProperties properties;

    @Autowired
    private OutboxVerificationCodeSender outbox;

    @Autowired
    private StringRedisTemplate redis;

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    private final List<String> keys = new ArrayList<>();

    @BeforeEach
    void resetState() {
        outbox.clear();
        redis.delete(List.of(
                registerCodes.keyOf(normalized()),
                registerCodes.failKeyOf(normalized()),
                registerCodes.cooldownKeyOf(normalized()),
                quota.keyOf(normalized())));
    }

    @AfterEach
    void cleanUp() {
        if (!keys.isEmpty()) {
            redis.delete(keys);
            keys.clear();
        }
    }

    @Test
    @DisplayName("日配额用尽后不再发码：429 + Retry-After，一条都不再投递")
    void blocksAfterDailyQuota() throws Exception {
        int dailyQuota = properties.emailCode().dailyQuota();
        for (int i = 0; i < dailyQuota; i++) {
            issueViaHttp();
        }
        assertThat(outbox.messages()).hasSize(dailyQuota);

        redis.delete(registerCodes.cooldownKeyOf(normalized()));
        CaptchaIssue blocked = captchaOf("ABCD");
        String retryAfter = post(blocked.captchaId(), "ABCD")
                .andExpect(status().isTooManyRequests())
                .andExpect(header().exists(HttpHeaders.RETRY_AFTER))
                .andExpect(jsonPath("$.code").value("RATE_LIMITED"))
                .andReturn().getResponse().getHeader(HttpHeaders.RETRY_AFTER);

        assertThat(Long.parseLong(retryAfter)).isPositive();
        assertThat(outbox.messages()).hasSize(dailyQuota);
    }

    @Test
    @DisplayName("Retry-After 指向次日零点")
    void retryAfterPointsToNextMidnight() {
        EmailQuotaGuard morning = guardAt("2026-10-28T10:00:00");
        keys.add(morning.keyOf(normalized()));

        // 10:00 到次日 00:00 是 14 小时。
        assertThat(morning.retryAfterSeconds()).isEqualTo(14 * 60 * 60L);
    }

    @Test
    @DisplayName("跨日重置：昨天的额度不占今天的")
    void quotaResetsNextDay() {
        EmailQuotaGuard today = guardAt("2026-10-28T23:59:00");
        EmailQuotaGuard tomorrow = guardAt("2026-10-29T00:01:00");
        keys.add(today.keyOf(normalized()));
        keys.add(tomorrow.keyOf(normalized()));

        int dailyQuota = properties.emailCode().dailyQuota();
        for (int i = 0; i < dailyQuota; i++) {
            assertThat(today.tryAcquire(normalized())).isTrue();
        }
        assertThat(today.tryAcquire(normalized())).isFalse();
        assertThat(tomorrow.tryAcquire(normalized())).isTrue();
    }

    @Test
    @DisplayName("图形码填错不消耗配额：否则任意人用错误答案就能耗光别人的额度")
    void wrongCaptchaDoesNotConsumeQuota() {
        String quotaKey = quota.keyOf(normalized());
        keys.add(quotaKey);

        for (int i = 0; i < properties.emailCode().dailyQuota() + 1; i++) {
            CaptchaIssue issue = captchaOf("ABCD");
            assertThatThrownBy(() -> registerCodes.issue(EMAIL, issue.captchaId(), "WXYZ"))
                    .isInstanceOf(BizException.class);
        }

        assertThat(redis.hasKey(quotaKey)).isFalse();
    }

    @Test
    @DisplayName("冷却命中不消耗配额：计数只增加一次，且拦住它的是冷却不是配额")
    void cooldownHitDoesNotConsumeQuota() {
        String quotaKey = quota.keyOf(normalized());
        keys.add(quotaKey);

        CaptchaIssue first = captchaOf("ABCD");
        registerCodes.issue(EMAIL, first.captchaId(), "ABCD");
        assertThat(redis.opsForValue().get(quotaKey)).isEqualTo("1");

        CaptchaIssue second = captchaOf("EFGH");
        assertThatThrownBy(() -> registerCodes.issue(EMAIL, second.captchaId(), "EFGH"))
                .isInstanceOf(BizException.class);

        assertThat(redis.opsForValue().get(quotaKey)).isEqualTo("1");
        // 图形码还在，说明请求停在冷却那道，没走到配额也没走到校验。
        assertThat(redis.hasKey(CAPTCHA_KEY_PREFIX + second.captchaId())).isTrue();
    }

    @Test
    @DisplayName("配额键不含明文邮箱，但带上当天日期")
    void quotaKeyHasNoPlaintextEmail() {
        String key = quota.keyOf(normalized());

        assertThat(key).doesNotContain("mallory").doesNotContain("@");
        assertThat(key).endsWith(":" + LocalDate.now());
    }

    private void issueViaHttp() throws Exception {
        redis.delete(registerCodes.cooldownKeyOf(normalized()));
        CaptchaIssue issue = captchaOf("ABCD");
        post(issue.captchaId(), "ABCD").andExpect(status().isAccepted());
    }

    private org.springframework.test.web.servlet.ResultActions post(String captchaId, String answer)
            throws Exception {
        String body = objectMapper.writeValueAsString(new RegisterCodeRequest(EMAIL, captchaId, answer));
        return mockMvc.perform(MockMvcRequestBuilders.post("/api/auth/register-code")
                .header(HttpHeaders.ORIGIN, ORIGIN)
                .contentType(MediaType.APPLICATION_JSON)
                .content(body));
    }

    private CaptchaIssue captchaOf(String answer) {
        CaptchaIssue issue = captcha.issue(answer);
        keys.add(CAPTCHA_KEY_PREFIX + issue.captchaId());
        return issue;
    }

    private EmailQuotaGuard guardAt(String localDateTime) {
        Clock clock = Clock.fixed(LocalDateTime.parse(localDateTime).atZone(ZONE).toInstant(), ZONE);
        return new EmailQuotaGuard(redis, hasher, properties, clock);
    }

    private static String normalized() {
        return EmailNormalizer.normalize(EMAIL);
    }
}
