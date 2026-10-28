package com.luyi.myfirst.service.auth;

import com.luyi.myfirst.service.auth.impl.CaptchaServiceImpl;
import com.luyi.myfirst.service.auth.impl.RegisterCodeServiceImpl;
import com.luyi.myfirst.service.auth.model.CaptchaIssue;
import com.luyi.myfirst.service.auth.support.EmailNormalizer;
import com.luyi.myfirst.service.auth.support.EmailQuotaGuard;
import com.luyi.myfirst.service.auth.support.OutboxVerificationCodeSender;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.luyi.myfirst.web.auth.dto.CaptchaResponse;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 图形码刷新的契约（tasks 4.6）：每次调用签发新的 captchaId，多张并存且各自独立可用，
 * 刷新本身不碰已发出的邮箱注册码。
 *
 * 「旧 captchaId 立即失效」在 4.6 处被修订为「并存」——端点纯无状态，请求里没有
 * 任何字段能说明「这是刚刚刷新页面的那个人」；强制作废要下发 cookie 记住当前 captchaId，
 * 为收益接近零的约束引入服务端会话状态，还会让两个标签页互相作废对方的图形码。
 *
 * 走 HTTP 的那几条验的是端点行为与副作用；「并存的两张都能换码」只能在服务层验——
 * 走 HTTP 拿到的图形码没有答案（答案生成后立刻被摘要，图里也没有文本）。
 */
@SpringBootTest
@AutoConfigureMockMvc
class CaptchaRefreshTest {

    private static final String CAPTCHA_KEY_PREFIX = "wc:captcha:";
    private static final String EMAIL = "ivan@example.com";
    private static final String OTHER_EMAIL = "judy@example.com";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private CaptchaServiceImpl captcha;

    @Autowired
    private RegisterCodeServiceImpl registerCodes;

    @Autowired
    private OutboxVerificationCodeSender outbox;

    @Autowired
    private EmailQuotaGuard quota;

    @Autowired
    private StringRedisTemplate redis;

    private final List<String> keys = new ArrayList<>();

    @BeforeEach
    void resetState() {
        outbox.clear();
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
    @DisplayName("每次调用都签发新的 captchaId，且各自在缓存里留了自己的键")
    void everyCallReturnsFreshCaptcha() throws Exception {
        CaptchaResponse first = fetch();
        CaptchaResponse second = fetch();

        assertThat(first.captchaId()).isNotEqualTo(second.captchaId());
        assertThat(UUID.fromString(first.captchaId())).isNotNull();
        assertThat(redis.hasKey(CAPTCHA_KEY_PREFIX + first.captchaId())).isTrue();
        assertThat(redis.hasKey(CAPTCHA_KEY_PREFIX + second.captchaId())).isTrue();
    }

    @Test
    @DisplayName("端点返回可渲染的图形：data URI 形式的 PNG")
    void returnsRenderableImage() throws Exception {
        CaptchaResponse response = fetch();

        assertThat(response.image()).startsWith("data:image/png;base64,");
    }

    @Test
    @DisplayName("并存的两张各自独立可用：先签发的不会因为后一张出现而失效")
    void bothIssuedCaptchasStayUsable() {
        CaptchaIssue first = captchaOf("ABCD");
        CaptchaIssue second = captchaOf("EFGH");

        assertThatCode(() -> registerCodes.issue(EMAIL, first.captchaId(), "ABCD"))
                .doesNotThrowAnyException();
        assertThatCode(() -> registerCodes.issue(OTHER_EMAIL, second.captchaId(), "EFGH"))
                .doesNotThrowAnyException();

        assertThat(outbox.messages()).hasSize(2);
    }

    @Test
    @DisplayName("反复刷新不影响已发出的注册码：摘要不变，码仍可校验通过")
    void refreshingDoesNotInvalidateIssuedCode() throws Exception {
        CaptchaIssue issue = captchaOf("ABCD");
        registerCodes.issue(EMAIL, issue.captchaId(), "ABCD");
        String code = outbox.messages().get(0).code();
        String key = keyOf(EMAIL);
        String digest = redis.opsForValue().get(key);

        fetch();
        fetch();

        assertThat(redis.opsForValue().get(key)).isEqualTo(digest);
        assertThatCode(() -> registerCodes.verify(EMAIL, code)).doesNotThrowAnyException();
    }

    private CaptchaResponse fetch() throws Exception {
        String body = mockMvc.perform(MockMvcRequestBuilders.get("/api/auth/captcha"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        CaptchaResponse response = objectMapper.readValue(body, CaptchaResponse.class);
        keys.add(CAPTCHA_KEY_PREFIX + response.captchaId());
        return response;
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

    private void resetCodeState(String email) {
        String normalized = EmailNormalizer.normalize(email);
        redis.delete(List.of(
                registerCodes.keyOf(normalized),
                registerCodes.failKeyOf(normalized),
                registerCodes.cooldownKeyOf(normalized),
                quota.keyOf(normalized)));
    }
}
