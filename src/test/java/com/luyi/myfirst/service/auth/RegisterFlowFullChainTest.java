package com.luyi.myfirst.service.auth;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.luyi.myfirst.common.enums.AccountStatus;
import com.luyi.myfirst.dao.auth.entity.Account;
import com.luyi.myfirst.dao.auth.repository.AccountRepository;
import com.luyi.myfirst.dao.auth.repository.AuthSessionRepository;
import com.luyi.myfirst.service.auth.impl.CaptchaServiceImpl;
import com.luyi.myfirst.service.auth.impl.RegisterCodeServiceImpl;
import com.luyi.myfirst.service.auth.model.CaptchaIssue;
import com.luyi.myfirst.service.auth.model.VerifiedToken;
import com.luyi.myfirst.service.auth.support.CodePurpose;
import com.luyi.myfirst.service.auth.support.EmailNormalizer;
import com.luyi.myfirst.service.auth.support.EmailQuotaGuard;
import com.luyi.myfirst.service.auth.support.OutboxVerificationCodeSender;
import com.luyi.myfirst.service.auth.support.TokenVerifier;
import com.luyi.myfirst.web.auth.AuthCookie;
import com.luyi.myfirst.web.auth.dto.RegisterCodeRequest;
import com.luyi.myfirst.web.auth.dto.RegisterRequest;
import jakarta.servlet.http.Cookie;
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
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.startsWith;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 注册全链路（tasks 4.7）：不联网跑通「取图形码 → 换注册码 → 注册」。
 *
 * 与 RegisterTest（5.1）的分工：那类在服务层直接调 {@code registerCodes.issue(...)} 取码，只验
 * 注册端点本身；本类把「换注册码」也走真实 HTTP 端点 {@code POST /api/auth/register-code}，让
 * 三步首尾相接地各产出一次真实响应，证明模拟发信端口（{@link OutboxVerificationCodeSender}，
 * {@code @Profile("!prod")}）足以支撑整条链路，不依赖任何外部邮件服务或网络。
 *
 * 「取图形码」这一步只能用 {@code CaptchaServiceImpl.issue(answer)} 这个 test-only 入口铸一张答案
 * 已知的图形码：{@code GET /api/auth/captcha} 的响应刻意不含答案（4.8 断言没有任何端点能读出码），
 * 因此纯 HTTP 无法解出自己刚取到的那张图形码——这不是链路缺口，而是安全属性。第一条用例另行断言
 * 该 HTTP 入口离线可用且不泄漏答案。
 *
 * 库内断言靠 {@code @Transactional} 回滚；Redis 不受事务管理，键自己登记、自己删。
 */
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class RegisterFlowFullChainTest {

    private static final String EMAIL = "chain@example.com";
    private static final String PASSWORD = "correct-horse-battery";
    private static final String ORIGIN = "http://localhost:3000";
    private static final String CAPTCHA_ANSWER = "ABCD";
    private static final String CAPTCHA_KEY_PREFIX = "wc:captcha:";

    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private RegisterCodeServiceImpl registerCodes;
    @Autowired private CaptchaServiceImpl captcha;
    @Autowired private OutboxVerificationCodeSender outbox;
    @Autowired private EmailQuotaGuard quota;
    @Autowired private StringRedisTemplate redis;
    @Autowired private AccountRepository accounts;
    @Autowired private AuthSessionRepository sessions;
    @Autowired private TokenVerifier verifier;

    private final List<String> keys = new ArrayList<>();

    @BeforeEach
    void resetState() {
        outbox.clear();
        String normalized = EmailNormalizer.normalize(EMAIL);
        redis.delete(List.of(
                registerCodes.keyOf(normalized),
                registerCodes.failKeyOf(normalized),
                registerCodes.cooldownKeyOf(normalized),
                quota.keyOf(normalized)));
    }

    @AfterEach
    void cleanUp() {
        if (!keys.isEmpty()) {
            redis.delete(keys);
            keys.clear();
        }
    }

    @Test
    @DisplayName("取图形码入口离线可用：返回 id 与 base64 PNG，且响应里没有答案")
    void captchaEndpointIsServedOfflineAndLeaksNoAnswer() throws Exception {
        MvcResult result = mockMvc.perform(MockMvcRequestBuilders.get("/api/auth/captcha"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.captchaId").isNotEmpty())
                .andExpect(jsonPath("$.image").value(startsWith("data:image/png;base64,")))
                .andReturn();

        String body = result.getResponse().getContentAsString(StandardCharsets.UTF_8);
        assertThat(body)
                .as("图形码响应只含 id 与图，答案不出现在响应里")
                .doesNotContain("answer")
                .doesNotContain(CAPTCHA_ANSWER);

        String captchaId = objectMapper.readTree(body).get("captchaId").asText();
        keys.add(CAPTCHA_KEY_PREFIX + captchaId);
    }

    @Test
    @DisplayName("全链路：图形码 → POST /register-code（投递进 outbox）→ POST /register 建 active 账户并下发 cookie")
    void registerCodeThenRegisterCompletesTheChainWithoutNetwork() throws Exception {
        String normalized = EmailNormalizer.normalize(EMAIL);
        registerCacheKeysForCleanup(normalized);

        // 第一步「取图形码」：铸一张答案已知的图形码（HTTP 响应不含答案，故走 test-only 入口）。
        CaptchaIssue issue = captcha.issue(CAPTCHA_ANSWER);
        keys.add(CAPTCHA_KEY_PREFIX + issue.captchaId());

        // 第二步「换注册码」：走真实 HTTP 端点，消费图形码并把明文码投递进进程内 outbox。
        String registerCodeBody = objectMapper.writeValueAsString(
                new RegisterCodeRequest(EMAIL, issue.captchaId(), CAPTCHA_ANSWER));
        mockMvc.perform(MockMvcRequestBuilders.post("/api/auth/register-code")
                        .header(HttpHeaders.ORIGIN, ORIGIN)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(registerCodeBody))
                .andExpect(status().isAccepted())
                .andExpect(header().doesNotExist(HttpHeaders.SET_COOKIE));

        // 不联网的直接证据：码落在进程内 outbox，而不是发往任何 SMTP；且换码端点响应体为空，码不回显。
        List<OutboxVerificationCodeSender.Message> delivered = outbox.messages();
        assertThat(delivered).as("链路不联网，投递只应落进进程内 outbox 一条").hasSize(1);
        OutboxVerificationCodeSender.Message message = delivered.get(0);
        assertThat(message.email()).isEqualTo(EMAIL);
        assertThat(message.purpose()).isEqualTo(CodePurpose.REGISTER);
        assertThat(message.code()).as("注册码是 6 位数字").matches("\\d{6}");

        // 第三步「注册」：拿 outbox 里的码走真实 HTTP 端点，建 active 账户并当场以 cookie 登录。
        String registerBody = objectMapper.writeValueAsString(
                new RegisterRequest(EMAIL, message.code(), PASSWORD, "Chain Walker"));
        MvcResult register = mockMvc.perform(MockMvcRequestBuilders.post("/api/auth/register")
                        .header(HttpHeaders.ORIGIN, ORIGIN)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(registerBody))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.publicId").isNotEmpty())
                .andExpect(jsonPath("$.email").value(EMAIL))
                .andExpect(header().string(HttpHeaders.SET_COOKIE, containsString("HttpOnly")))
                .andExpect(header().string(HttpHeaders.SET_COOKIE, containsString("SameSite=Lax")))
                .andExpect(header().string(HttpHeaders.SET_COOKIE, containsString("Path=/")))
                .andReturn();

        // 响应体不含令牌明文，令牌只从 cookie 出去，且能被校验点验回、会话行落库。
        String registerResponseBody = register.getResponse().getContentAsString(StandardCharsets.UTF_8);
        Cookie cookie = register.getResponse().getCookie(AuthCookie.NAME);
        assertThat(cookie).as("注册成功应当场下发认证 cookie").isNotNull();
        String token = Optional.ofNullable(cookie.getValue()).orElseThrow();
        assertThat(registerResponseBody).doesNotContain(token).doesNotContain("token");

        Account account = accounts.findByEmailAndDeletedAtIsNull(EMAIL).orElseThrow();
        assertThat(account.status()).isEqualTo(AccountStatus.ACTIVE);
        assertThat(account.publicId()).hasSize(26);

        VerifiedToken verified = verifier.verify(token).orElseThrow();
        assertThat(verified.account().publicId()).isEqualTo(account.publicId());
        assertThat(sessions.findByJti(verified.jti())).isPresent();
    }

    private void registerCacheKeysForCleanup(String normalized) {
        keys.add(registerCodes.keyOf(normalized));
        keys.add(registerCodes.failKeyOf(normalized));
        keys.add(registerCodes.cooldownKeyOf(normalized));
        keys.add(quota.keyOf(normalized));
    }
}
