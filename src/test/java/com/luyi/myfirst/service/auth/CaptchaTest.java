package com.luyi.myfirst.service.auth;

import com.luyi.myfirst.service.auth.impl.CaptchaServiceImpl;
import com.luyi.myfirst.service.auth.model.CaptchaIssue;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.luyi.myfirst.web.auth.dto.CaptchaResponse;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 图形码的四条契约（D9 + tasks 4.1）：答案只以摘要入缓存、一次性、答错即作废、可重复获取。
 *
 * 「答对能通过」这条断言需要知道答案，而生产路径的答案生成后立刻被摘要、图片里也没有可读文本，
 * 因此测试走包级的 issue(answer) 指定答案——这是本类唯一依赖测试专用入口的地方。
 */
@SpringBootTest
@AutoConfigureMockMvc
class CaptchaTest {

    private static final String KEY_PREFIX = "wc:captcha:";

    @Autowired
    private CaptchaServiceImpl captcha;

    @Autowired
    private StringRedisTemplate redis;

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    private final List<String> issued = new ArrayList<>();

    @AfterEach
    void cleanUp() {
        if (!issued.isEmpty()) {
            redis.delete(issued.stream().map(id -> KEY_PREFIX + id).toList());
            issued.clear();
        }
    }

    @Test
    @DisplayName("签发：id 是 UUID，图是可直接渲染的 PNG data URI")
    void issuesIdAndPngImage() {
        CaptchaIssue issue = captcha.issue();
        issued.add(issue.captchaId());

        assertThatCode(() -> UUID.fromString(issue.captchaId())).doesNotThrowAnyException();
        assertThat(issue.image()).startsWith("data:image/png;base64,");

        String base64 = issue.image().substring("data:image/png;base64,".length());
        assertThat(Base64.getDecoder().decode(base64))
                .startsWith((byte) 0x89, (byte) 0x50, (byte) 0x4E, (byte) 0x47);
    }

    @Test
    @DisplayName("缓存里只有摘要：既没有明文答案，键名里也没有")
    void storesDigestOnly() {
        CaptchaIssue issue = issue("ABCD");
        String key = KEY_PREFIX + issue.captchaId();

        String stored = redis.opsForValue().get(key);

        assertThat(stored).matches("[0-9a-f]{64}");
        assertThat(stored).doesNotContain("ABCD");
        assertThat(key).doesNotContain("ABCD");
    }

    @Test
    @DisplayName("答对即通过，并且立刻作废：同一个 id 第二次就不认了")
    void acceptsCorrectAnswerOnce() {
        CaptchaIssue issue = issue("ABCD");

        assertThat(captcha.verify(issue.captchaId(), "ABCD")).isTrue();
        assertThat(captcha.verify(issue.captchaId(), "ABCD")).isFalse();
    }

    @Test
    @DisplayName("答错即作废：同一个 id 之后连正确答案也不认")
    void wrongAnswerBurnsTheCaptcha() {
        CaptchaIssue issue = issue("ABCD");

        assertThat(captcha.verify(issue.captchaId(), "WXYZ")).isFalse();
        assertThat(captcha.verify(issue.captchaId(), "ABCD")).isFalse();
    }

    @Test
    @DisplayName("不存在的 id：失败而不是抛异常——并发提交同一张图不该变成 500")
    void rejectsUnknownId() {
        assertThat(captcha.verify(UUID.randomUUID().toString(), "ABCD")).isFalse();
    }

    @Test
    @DisplayName("大小写与首尾空白不影响判对")
    void isCaseInsensitiveAndTrimmed() {
        CaptchaIssue issue = issue("ABCD");

        assertThat(captcha.verify(issue.captchaId(), "  abcd  ")).isTrue();
    }

    @Test
    @DisplayName("存活时间取配置的 2 分钟，不硬编码在业务分支里")
    void setsConfiguredTtl() {
        CaptchaIssue issue = issue("ABCD");

        Long ttl = redis.getExpire(KEY_PREFIX + issue.captchaId(), TimeUnit.SECONDS);

        assertThat(ttl).isNotNull().isBetween(1L, 120L);
    }

    @Test
    @DisplayName("过期后失效：缓存自己不认这张图了")
    void expiresAfterTtl() throws Exception {
        CaptchaIssue issue = issue("ABCD");
        redis.expire(KEY_PREFIX + issue.captchaId(), Duration.ofMillis(100));

        Thread.sleep(300);

        assertThat(captcha.verify(issue.captchaId(), "ABCD")).isFalse();
    }

    @Test
    @DisplayName("GET /api/auth/captcha 无需认证：公开端点确实被排除在受保护路径之外")
    void servesCaptchaWithoutAuthentication() throws Exception {
        String body = mockMvc.perform(get("/api/auth/captcha"))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();

        CaptchaResponse response = objectMapper.readValue(body, CaptchaResponse.class);
        issued.add(response.captchaId());

        assertThat(response.image()).startsWith("data:image/png;base64,");
        assertThatCode(() -> UUID.fromString(response.captchaId())).doesNotThrowAnyException();
    }

    private CaptchaIssue issue(String answer) {
        CaptchaIssue issue = captcha.issue(answer);
        issued.add(issue.captchaId());
        return issue;
    }
}
