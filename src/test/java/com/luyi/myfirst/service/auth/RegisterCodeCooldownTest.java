package com.luyi.myfirst.service.auth;

import com.luyi.myfirst.service.auth.impl.CaptchaServiceImpl;
import com.luyi.myfirst.service.auth.impl.RegisterCodeServiceImpl;
import com.luyi.myfirst.service.auth.model.CaptchaIssue;
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
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 重发冷却的契约（tasks 4.5）：冷却内重发 429 + Retry-After，不生成新码、不投递。
 *
 * 走 HTTP 而不是直接调 service：Retry-After 是响应头，只有穿过 GlobalExceptionHandler 才算真的发得出去。
 *
 * 冷却「先查、后设，且设在图形码通过之后」这一顺序，靠两条断言钉住：
 * 冷却命中时图形码仍在缓存里（没被白烧），图形码填错时冷却键不存在（没被误锁 60 秒）。
 */
@SpringBootTest
@AutoConfigureMockMvc
class RegisterCodeCooldownTest {

    private static final String EMAIL = "grace@example.com";
    private static final String OTHER_EMAIL = "heidi@example.com";
    private static final String ORIGIN = "http://localhost:3000";
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

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    private final List<String> keys = new ArrayList<>();

    @BeforeEach
    void resetState() {
        outbox.clear();
        // 主动重置而不是只靠事后清理：冷却键一旦漏登记，它会以 60 秒的寿命
        // 跨过好几个测试方法，让下一个测试在第一个请求就 429 —— 那种失败现场极难归因。
        resetCodeState(EMAIL);
        resetCodeState(OTHER_EMAIL);
    }

    @AfterEach
    void cleanUp() {
        if (!keys.isEmpty()) {
            redis.delete(keys);
            keys.clear();
        }
    }

    @Test
    @DisplayName("冷却窗口内重发：429 + Retry-After，不生成新码也不投递")
    void rejectsResendWithinCooldown() throws Exception {
        issueVia(EMAIL, "ABCD");
        String key = keyOf(EMAIL);
        String originalDigest = redis.opsForValue().get(key);

        CaptchaIssue second = captchaOf("EFGH");
        String retryAfter = post(EMAIL, second.captchaId(), "EFGH")
                .andExpect(status().isTooManyRequests())
                .andExpect(header().exists(HttpHeaders.RETRY_AFTER))
                .andExpect(jsonPath("$.code").value("RATE_LIMITED"))
                .andReturn().getResponse().getHeader(HttpHeaders.RETRY_AFTER);

        long seconds = properties.emailCode().resendCooldown().toSeconds();
        assertThat(Long.parseLong(retryAfter)).isBetween(1L, seconds);
        assertThat(outbox.messages()).hasSize(1);
        assertThat(redis.opsForValue().get(key)).isEqualTo(originalDigest);
    }

    @Test
    @DisplayName("冷却命中时不烧图形码：拦住重发的是冷却，不是图形码")
    void cooldownHitDoesNotBurnCaptcha() throws Exception {
        issueVia(EMAIL, "ABCD");

        CaptchaIssue second = captchaOf("EFGH");
        post(EMAIL, second.captchaId(), "EFGH").andExpect(status().isTooManyRequests());

        assertThat(redis.hasKey(CAPTCHA_KEY_PREFIX + second.captchaId())).isTrue();
    }

    @Test
    @DisplayName("图形码填错不启动冷却：否则打错一次字就被锁 60 秒")
    void wrongCaptchaDoesNotStartCooldown() throws Exception {
        CaptchaIssue issue = captchaOf("ABCD");

        post(EMAIL, issue.captchaId(), "WXYZ")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("CAPTCHA_INVALID"));

        assertThat(redis.hasKey(registerCodes.cooldownKeyOf(EmailNormalizer.normalize(EMAIL))))
                .isFalse();

        issueVia(EMAIL, "ABCD");
    }

    @Test
    @DisplayName("冷却按邮箱计：一个邮箱在冷却里，另一个邮箱照常发码")
    void cooldownIsPerEmail() throws Exception {
        issueVia(EMAIL, "ABCD");

        issueVia(OTHER_EMAIL, "EFGH");

        assertThat(outbox.messages()).hasSize(2);
        assertThat(outbox.messages().get(1).email()).isEqualTo(OTHER_EMAIL);
    }

    @Test
    @DisplayName("冷却到期后可重发，且旧码随之失效")
    void resendAfterCooldownInvalidatesPreviousCode() throws Exception {
        String first = issueCodeViaService("ABCD");

        // 把冷却键的存活时间压到 1ms 再等它消失 —— 模拟「冷却到期」，而不是绕过冷却。
        String cooldownKey = registerCodes.cooldownKeyOf(EmailNormalizer.normalize(EMAIL));
        redis.expire(cooldownKey, Duration.ofMillis(1));
        awaitGone(cooldownKey);

        String second = issueCodeViaService("EFGH");

        assertThat(second).isNotEqualTo(first);
        assertRejected(first);
        assertThatCode(() -> registerCodes.verify(EMAIL, second)).doesNotThrowAnyException();
    }

    private void resetCodeState(String email) {
        String normalized = EmailNormalizer.normalize(email);
        redis.delete(List.of(
                registerCodes.keyOf(normalized),
                registerCodes.failKeyOf(normalized),
                registerCodes.cooldownKeyOf(normalized),
                quota.keyOf(normalized)));
    }

    private void issueVia(String email, String answer) throws Exception {
        CaptchaIssue issue = captchaOf(answer);
        post(email, issue.captchaId(), answer).andExpect(status().isAccepted());
        keyOf(email);
    }

    private String issueCodeViaService(String answer) {
        CaptchaIssue issue = captchaOf(answer);
        registerCodes.issue(EMAIL, issue.captchaId(), answer);
        keyOf(EMAIL);
        List<OutboxVerificationCodeSender.Message> messages = outbox.messages();
        return messages.get(messages.size() - 1).code();
    }

    private CaptchaIssue captchaOf(String answer) {
        CaptchaIssue issue = captcha.issue(answer);
        keys.add(CAPTCHA_KEY_PREFIX + issue.captchaId());
        return issue;
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

    private void assertRejected(String code) {
        assertThatThrownBy(() -> registerCodes.verify(EMAIL, code))
                .isInstanceOf(BizException.class)
                .extracting(e -> ((BizException) e).getErrorCode())
                .isEqualTo("INVALID_CODE");
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

    private ResultActions post(String email, String captchaId, String answer) throws Exception {
        String body = objectMapper.writeValueAsString(new RegisterCodeRequest(email, captchaId, answer));
        return mockMvc.perform(MockMvcRequestBuilders.post("/api/auth/register-code")
                .header(HttpHeaders.ORIGIN, ORIGIN)
                .contentType(MediaType.APPLICATION_JSON)
                .content(body));
    }
}
