package com.luyi.myfirst.service.auth;

import com.luyi.myfirst.service.auth.impl.CaptchaServiceImpl;
import com.luyi.myfirst.service.auth.impl.RegisterCodeServiceImpl;
import com.luyi.myfirst.service.auth.model.CaptchaIssue;
import com.luyi.myfirst.service.auth.model.VerifiedToken;
import com.luyi.myfirst.service.auth.support.EmailNormalizer;
import com.luyi.myfirst.service.auth.support.EmailQuotaGuard;
import com.luyi.myfirst.service.auth.support.OutboxVerificationCodeSender;
import com.luyi.myfirst.service.auth.support.TokenVerifier;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.luyi.myfirst.common.enums.AccountStatus;
import com.luyi.myfirst.dao.auth.entity.Account;
import com.luyi.myfirst.dao.auth.repository.AccountRepository;
import com.luyi.myfirst.dao.auth.repository.AuthSessionRepository;
import com.luyi.myfirst.web.auth.AuthCookie;
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
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 注册端点的契约（tasks 5.1）：码对即建 active 账户并以 cookie 登录，码错或过时不建账户。
 *
 * 走 HTTP 而不是直接调 service：令牌只从 Set-Cookie 出去、响应体里检索不到、
 * 400 的响应体形状，这三条都只有穿过 GlobalExceptionHandler 与真实序列化才算数。
 *
 * 库里断言靠 {@code @Transactional} 回滚；Redis 不受事务管理，所以键要自己登记、自己删。
 */
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class RegisterTest {

    private static final String EMAIL = "dana@example.com";
    private static final String PASSWORD = "correct-horse-battery";
    private static final String ORIGIN = "http://localhost:3000";
    private static final String CAPTCHA_ANSWER = "ABCD";
    private static final String CAPTCHA_KEY_PREFIX = "wc:captcha:";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

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
    private AccountRepository accounts;

    @Autowired
    private AuthSessionRepository sessions;

    @Autowired
    private TokenVerifier verifier;

    private final List<String> keys = new ArrayList<>();

    @BeforeEach
    void resetState() {
        outbox.clear();
        resetCodeState(EMAIL);
    }

    @AfterEach
    void cleanUp() {
        if (!keys.isEmpty()) {
            redis.delete(keys);
            keys.clear();
        }
    }

    @Test
    @DisplayName("码对：建 active 账户，令牌只经 cookie 下发，且当场可用来证明身份")
    void registersAsActiveAccountAndSignsIn() throws Exception {
        String code = issueCode();

        MvcResult result = post(EMAIL, code, PASSWORD, "Dana the Nomad")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.publicId").isNotEmpty())
                .andExpect(jsonPath("$.email").value(EMAIL))
                .andExpect(jsonPath("$.displayName").value("Dana the Nomad"))
                .andExpect(header().string(HttpHeaders.SET_COOKIE, containsString("HttpOnly")))
                .andExpect(header().string(HttpHeaders.SET_COOKIE, containsString("SameSite=Lax")))
                .andExpect(header().string(HttpHeaders.SET_COOKIE, containsString("Path=/")))
                .andReturn();

        Account account = accounts.findByEmailAndDeletedAtIsNull(EMAIL).orElseThrow();
        assertThat(account.status()).isEqualTo(AccountStatus.ACTIVE);
        assertThat(account.publicId()).hasSize(26);

        String token = tokenOf(result);
        VerifiedToken verified = verifier.verify(token).orElseThrow();
        assertThat(verified.account().publicId()).isEqualTo(account.publicId());
        assertThat(sessions.findByJti(verified.jti())).isPresent();
        assertThat(sessions.findByJti(verified.jti()).orElseThrow().isRevoked()).isFalse();
    }

    @Test
    @DisplayName("令牌不出现在响应体：整段 body 里检索不到它")
    void responseBodyCarriesNoToken() throws Exception {
        String code = issueCode();

        MvcResult result = post(EMAIL, code, PASSWORD, null)
                .andExpect(status().isOk())
                .andReturn();

        String body = result.getResponse().getContentAsString(StandardCharsets.UTF_8);
        assertThat(body).doesNotContain(tokenOf(result));
        assertThat(body).doesNotContain("token");
    }

    @Test
    @DisplayName("码错：400 + INVALID_CODE，不建账户，也不下发 cookie")
    void rejectsWrongCodeWithoutCreatingAccount() throws Exception {
        issueCode();

        post(EMAIL, "000000", PASSWORD, null)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_CODE"))
                .andExpect(header().doesNotExist(HttpHeaders.SET_COOKIE));

        assertThat(accounts.findByEmailAndDeletedAtIsNull(EMAIL)).isEmpty();
    }

    @Test
    @DisplayName("码过时：键已不在缓存，400 + INVALID_CODE，不建账户")
    void rejectsExpiredCodeWithoutCreatingAccount() throws Exception {
        String code = issueCode();
        redis.delete(registerCodes.keyOf(EMAIL));

        post(EMAIL, code, PASSWORD, null)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_CODE"));

        assertThat(accounts.findByEmailAndDeletedAtIsNull(EMAIL)).isEmpty();
    }

    @Test
    @DisplayName("同一个码只能用一次：第二次提交不建第二个账户")
    void sameCodeCannotBeUsedTwice() throws Exception {
        String code = issueCode();
        post(EMAIL, code, PASSWORD, null).andExpect(status().isOk());

        post(EMAIL, code, PASSWORD, null)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_CODE"));

        assertThat(accounts.findAll().stream().filter(a -> EMAIL.equals(a.email())).count()).isEqualTo(1);
    }

    @Test
    @DisplayName("密码 7 字节：400 + VALIDATION_FAILED，且不消耗注册码")
    void rejectsShortPasswordWithoutBurningTheCode() throws Exception {
        String code = issueCode();

        post(EMAIL, code, "1234567", null)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));

        assertThat(accounts.findByEmailAndDeletedAtIsNull(EMAIL)).isEmpty();
        // 顺序契约：格式校验排在验码之前，用户改完密码还能用同一张码。
        post(EMAIL, code, PASSWORD, null).andExpect(status().isOk());
    }

    @Test
    @DisplayName("密码 73 字节：400 + VALIDATION_FAILED，不建账户")
    void rejectsPasswordLongerThanBcryptLimit() throws Exception {
        String code = issueCode();

        post(EMAIL, code, "a".repeat(73), null)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));

        assertThat(accounts.findByEmailAndDeletedAtIsNull(EMAIL)).isEmpty();
    }

    @Test
    @DisplayName("长度按 UTF-8 字节算：8 个汉字（24 字节）可以注册")
    void measuresPasswordInBytesNotCharacters() throws Exception {
        String code = issueCode();

        post(EMAIL, code, "八个汉字密码安全", null).andExpect(status().isOk());

        assertThat(accounts.findByEmailAndDeletedAtIsNull(EMAIL)).isPresent();
    }

    @Test
    @DisplayName("邮箱先去空白再转小写：库里存的是规范化后的值")
    void normalizesEmailBeforeCreatingAccount() throws Exception {
        String code = issueCode();

        post("  DANA@Example.COM  ", code, PASSWORD, null).andExpect(status().isOk());

        Account account = accounts.findByEmailAndDeletedAtIsNull(EMAIL).orElseThrow();
        assertThat(account.email()).isEqualTo(EMAIL);
    }

    @Test
    @DisplayName("displayName 缺省：取邮箱 @ 前那一段")
    void defaultsDisplayNameToEmailLocalPart() throws Exception {
        String code = issueCode();

        post(EMAIL, code, PASSWORD, null)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.displayName").value("dana"));
    }

    @Test
    @DisplayName("displayName 显式传入：去掉首尾空白后落库")
    void keepsExplicitDisplayName() throws Exception {
        String code = issueCode();

        post(EMAIL, code, PASSWORD, "  Dana  ")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.displayName").value("Dana"));
    }

    @Test
    @DisplayName("已注册邮箱再次注册：与码错误同形，不产生第二份账户")
    void rejectsSecondRegistrationOfTheSameEmail() throws Exception {
        post(EMAIL, issueCode(), PASSWORD, null).andExpect(status().isOk());

        // 已注册邮箱还能拿到码这件事本身由 5.2 堵住；这里要守的是「即便持码也建不出第二个账户」。
        post(EMAIL, issueCode(), PASSWORD, null)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_CODE"));

        assertThat(accounts.findAll().stream().filter(a -> EMAIL.equals(a.email())).count()).isEqualTo(1);
    }

    /** 取一张可用的注册码：先拆掉冷却与日配额，它们不是本类的被测对象。 */
    private String issueCode() {
        String normalized = EmailNormalizer.normalize(EMAIL);
        redis.delete(List.of(registerCodes.cooldownKeyOf(normalized), quota.keyOf(normalized)));
        CaptchaIssue issue = captcha.issue(CAPTCHA_ANSWER);
        keys.add(CAPTCHA_KEY_PREFIX + issue.captchaId());
        registerCodes.issue(EMAIL, issue.captchaId(), CAPTCHA_ANSWER);
        keyOf(EMAIL);
        List<OutboxVerificationCodeSender.Message> messages = outbox.messages();
        return messages.get(messages.size() - 1).code();
    }

    private ResultActions post(String email, String code, String password, String displayName) throws Exception {
        String body = objectMapper.writeValueAsString(new RegisterRequest(email, code, password, displayName));
        return mockMvc.perform(MockMvcRequestBuilders.post("/api/auth/register")
                .header(HttpHeaders.ORIGIN, ORIGIN)
                .contentType(MediaType.APPLICATION_JSON)
                .content(body));
    }

    private String tokenOf(MvcResult result) {
        Cookie cookie = result.getResponse().getCookie(AuthCookie.NAME);
        assertThat(cookie).isNotNull();
        return Optional.ofNullable(cookie.getValue()).orElseThrow();
    }

    private void keyOf(String email) {
        String normalized = EmailNormalizer.normalize(email);
        keys.add(registerCodes.keyOf(normalized));
        keys.add(registerCodes.failKeyOf(normalized));
        keys.add(registerCodes.cooldownKeyOf(normalized));
        keys.add(quota.keyOf(normalized));
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
