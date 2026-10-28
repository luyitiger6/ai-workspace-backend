package com.luyi.myfirst.service.auth;

import com.luyi.myfirst.service.auth.impl.CaptchaServiceImpl;
import com.luyi.myfirst.service.auth.impl.RegisterCodeServiceImpl;
import com.luyi.myfirst.service.auth.model.CaptchaIssue;
import com.luyi.myfirst.service.auth.support.CodePurpose;
import com.luyi.myfirst.service.auth.support.EmailNormalizer;
import com.luyi.myfirst.service.auth.support.EmailQuotaGuard;
import com.luyi.myfirst.service.auth.support.OutboxVerificationCodeSender;

import com.fasterxml.jackson.databind.ObjectMapper;
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

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 注册码换发的契约（tasks 4.2）：图形码通过才发码、失败一条都不投递、图形码用掉就没了。
 *
 * 所有请求都带 Origin —— 这是写请求，生产装配里 OriginGuard 就挂在这条路径上。
 * 「不带 Origin」本身也是一条用例：它同时证明了来源校验在真实端点上生效，
 * 且拦截发生在业务之前（图形码没被消耗）。
 *
 * 明文码从 outbox 读：它只存在于投递通道里，没有任何 HTTP 端点能取到它（D8）。
 */
@SpringBootTest
@AutoConfigureMockMvc
class RegisterCodeTest {

    private static final String ORIGIN = "http://localhost:3000";
    private static final String CAPTCHA_KEY_PREFIX = "wc:captcha:";

    @Autowired
    private CaptchaServiceImpl captcha;

    @Autowired
    private RegisterCodeServiceImpl registerCodes;

    @Autowired
    private EmailQuotaGuard quota;

    @Autowired
    private OutboxVerificationCodeSender outbox;

    @Autowired
    private StringRedisTemplate redis;

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    private final List<String> captchaIds = new ArrayList<>();
    private final List<String> codeKeys = new ArrayList<>();

    @BeforeEach
    void resetOutbox() {
        outbox.clear();
    }

    @AfterEach
    void cleanUp() {
        if (!captchaIds.isEmpty()) {
            redis.delete(captchaIds.stream().map(id -> CAPTCHA_KEY_PREFIX + id).toList());
            captchaIds.clear();
        }
        if (!codeKeys.isEmpty()) {
            redis.delete(codeKeys);
            codeKeys.clear();
        }
    }

    @Test
    @DisplayName("图形码通过：202、投递一条 6 位数字码、缓存只留摘要")
    void issuesCodeAndDelivers() throws Exception {
        CaptchaIssue issue = captchaOf("ABCD");

        post("alice@example.com", issue.captchaId(), "ABCD", true)
                .andExpect(status().isAccepted());

        assertThat(outbox.messages()).hasSize(1);
        OutboxVerificationCodeSender.Message message = outbox.messages().get(0);
        assertThat(message.email()).isEqualTo("alice@example.com");
        assertThat(message.code()).matches("\\d{6}");
        assertThat(message.purpose()).isEqualTo(CodePurpose.REGISTER);
        assertThat(redis.opsForValue().get(keyOf("alice@example.com"))).matches("[0-9a-f]{64}");
    }

    @Test
    @DisplayName("图形码错误：400 与 CAPTCHA_INVALID，一条都不投递，缓存也不留痕")
    void rejectsWrongCaptchaWithoutDelivering() throws Exception {
        CaptchaIssue issue = captchaOf("ABCD");
        String key = keyOf("bob@example.com");

        post("bob@example.com", issue.captchaId(), "WXYZ", true)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("CAPTCHA_INVALID"));

        assertThat(outbox.messages()).isEmpty();
        assertThat(redis.hasKey(key)).isFalse();
    }

    @Test
    @DisplayName("图形码用掉就没了：同一个 captchaId 换一个邮箱提交，仍被拒")
    void consumesCaptchaOnFirstUse() throws Exception {
        CaptchaIssue issue = captchaOf("ABCD");
        keyOf("carol@example.com");

        post("carol@example.com", issue.captchaId(), "ABCD", true)
                .andExpect(status().isAccepted());
        // 同邮箱再提一次会先撞上重发冷却（429），看不到「图形码已消耗」这件事；
        // 冷却按邮箱计，换个邮箱就把它绕开了，撞上的才是图形码本身。
        post("carol.alt@example.com", issue.captchaId(), "ABCD", true)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("CAPTCHA_INVALID"));
    }

    @Test
    @DisplayName("缓存里没有明文：值只是摘要，键里也没有邮箱")
    void storesDigestOnly() throws Exception {
        CaptchaIssue issue = captchaOf("ABCD");

        post("dave@example.com", issue.captchaId(), "ABCD", true)
                .andExpect(status().isAccepted());

        String key = keyOf("dave@example.com");
        String stored = redis.opsForValue().get(key);
        String plaintext = outbox.messages().get(0).code();

        assertThat(stored).isNotNull().doesNotContain(plaintext);
        assertThat(key).doesNotContain("dave").doesNotContain("@");
    }

    @Test
    @DisplayName("邮箱先规范化：大小写与首尾空白不影响缓存键，也不影响投递地址")
    void normalizesEmail() throws Exception {
        CaptchaIssue issue = captchaOf("ABCD");

        post("  Alice@Example.COM  ", issue.captchaId(), "ABCD", true)
                .andExpect(status().isAccepted());

        assertThat(outbox.messages().get(0).email()).isEqualTo("alice@example.com");
        assertThat(redis.hasKey(keyOf("alice@example.com"))).isTrue();
    }

    @Test
    @DisplayName("邮箱为空：400 与 VALIDATION_FAILED，且不消耗图形码——无效请求不该烧掉一次性凭证")
    void rejectsBlankEmailWithoutBurningCaptcha() throws Exception {
        CaptchaIssue issue = captchaOf("ABCD");

        post("   ", issue.captchaId(), "ABCD", true)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));

        assertThat(outbox.messages()).isEmpty();
        assertThat(redis.hasKey(CAPTCHA_KEY_PREFIX + issue.captchaId())).isTrue();
    }

    @Test
    @DisplayName("跨站写请求：来源校验先拦下，403 CROSS_SITE_REJECTED，图形码不被消耗")
    void rejectsCrossSiteBeforeAnything() throws Exception {
        CaptchaIssue issue = captchaOf("ABCD");

        post("eve@example.com", issue.captchaId(), "ABCD", false)
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("CROSS_SITE_REJECTED"));

        assertThat(outbox.messages()).isEmpty();
        assertThat(redis.hasKey(CAPTCHA_KEY_PREFIX + issue.captchaId())).isTrue();
    }

    private CaptchaIssue captchaOf(String answer) {
        CaptchaIssue issue = captcha.issue(answer);
        captchaIds.add(issue.captchaId());
        return issue;
    }

    private String keyOf(String email) {
        String normalized = EmailNormalizer.normalize(email);
        String key = registerCodes.keyOf(normalized);
        codeKeys.add(key);
        codeKeys.add(registerCodes.cooldownKeyOf(normalized));
        codeKeys.add(quota.keyOf(normalized));
        return key;
    }

    private ResultActions post(String email, String captchaId, String answer, boolean withOrigin)
            throws Exception {
        String body = objectMapper.writeValueAsString(new RegisterCodeRequest(email, captchaId, answer));
        var request = MockMvcRequestBuilders.post("/api/auth/register-code")
                .contentType(MediaType.APPLICATION_JSON)
                .content(body);
        if (withOrigin) {
            request.header(HttpHeaders.ORIGIN, ORIGIN);
        }
        return mockMvc.perform(request);
    }
}
