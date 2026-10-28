package com.luyi.myfirst.service.auth;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.luyi.myfirst.common.id.Ulid;
import com.luyi.myfirst.common.security.PasswordHasher;
import com.luyi.myfirst.config.AuthProperties;
import com.luyi.myfirst.dao.auth.entity.Account;
import com.luyi.myfirst.dao.auth.repository.AccountRepository;
import com.luyi.myfirst.service.auth.impl.CaptchaServiceImpl;
import com.luyi.myfirst.service.auth.impl.RegisterCodeServiceImpl;
import com.luyi.myfirst.service.auth.model.CaptchaIssue;
import com.luyi.myfirst.service.auth.support.EmailNormalizer;
import com.luyi.myfirst.service.auth.support.EmailQuotaGuard;
import com.luyi.myfirst.service.auth.support.LoginThrottle;
import com.luyi.myfirst.service.auth.support.OutboxVerificationCodeSender;
import com.luyi.myfirst.web.auth.dto.LoginRequest;
import com.luyi.myfirst.web.auth.dto.RegisterRequest;
import com.luyi.myfirst.web.auth.interceptor.SourceKeyResolver;
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
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 入参规范化的契约（tasks 5.8）：邮箱 trim + 小写，密码长度按 UTF-8 字节计、限定 8–72。
 *
 * 注册侧的三条（7 字节、73 字节、大小写落库）在 {@code RegisterTest} 已落地；
 * 本类补的是登录侧——注册与登录共用同一套入参约束，而登录曾经只判空。
 *
 * 上限不是口味问题：bcrypt 只读前 72 字节，「72 字节的密码」与「同样 72 字节再跟
 * 任意后缀」本来就是同一个等价类，入口不拒就会静默压小密码空间。
 */
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class CredentialsNormalizationTest {

    private static final String EMAIL = "gina@example.com";
    private static final String CASE_VARIANT = "  GINA@Example.COM  ";
    private static final String PASSWORD = "correct-horse-battery";
    private static final String ORIGIN = "http://localhost:3000";
    private static final String SEVEN_BYTES = "1234567";
    private static final String CAPTCHA_ANSWER = "ABCD";
    private static final String CAPTCHA_KEY_PREFIX = "wc:captcha:";
    private static final String PASSWORD_72 = "a".repeat(72);

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private AccountRepository accounts;

    @Autowired
    private PasswordHasher hasher;

    @Autowired
    private LoginThrottle throttle;

    @Autowired
    private AuthProperties properties;

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

    @MockitoBean
    private SourceKeyResolver sourceKey;

    /** 每个用例一个来源：本类多数用例会制造失败，共用来源键会让用例互相限流。 */
    private String source;

    private final List<String> keys = new ArrayList<>();

    /** 账户失败计数在共享缓存里，数据库回滚救不了它。 */
    private Long accountIdToClean;

    @BeforeEach
    void isolateSource() {
        source = "credentials-test-" + UUID.randomUUID();
        when(sourceKey.resolve(any())).thenReturn(source);
        outbox.clear();
        redis.delete(codeKeysOf(EMAIL));
    }

    @AfterEach
    void cleanUp() {
        if (accountIdToClean != null) {
            throttle.clearAccountFailures(accountIdToClean);
        }
        if (!keys.isEmpty()) {
            redis.delete(keys);
            keys.clear();
        }
    }

    @Test
    @DisplayName("登录密码 7 字节：400，且密码压根没被比对过")
    void shortPasswordIsRejectedBeforePasswordIsCompared() throws Exception {
        Account account = saveActive(PASSWORD);
        // 先把账户推到「只差一次失败就要图形码」。格式校验若排在密码比对之后，
        // 这次请求会被记成一次密码错误，计数随之越线。
        for (int i = 0; i < properties.login().failThreshold() - 1; i++) {
            throttle.recordFailure(account.id(), source);
        }
        assertThat(throttle.requiresCaptcha(account.id())).isFalse();

        login(EMAIL, SEVEN_BYTES)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                .andExpect(header().doesNotExist(HttpHeaders.SET_COOKIE));

        assertThat(throttle.requiresCaptcha(account.id()))
                .as("走到密码比对就会记一次失败，把计数推过阈值")
                .isFalse();
    }

    @Test
    @DisplayName("登录密码 73 字节：400，不下发 cookie")
    void overlongPasswordIsRejectedOnLogin() throws Exception {
        saveActive(PASSWORD);

        MvcResult result = login(EMAIL, PASSWORD_72 + "x")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                .andReturn();

        assertThat(result.getResponse().getHeader(HttpHeaders.SET_COOKIE)).isNull();
    }

    @Test
    @DisplayName("前缀相同但超出 72 字节的密码不得登入——bcrypt 只读前 72 字节")
    void passwordBeyondBcryptWindowIsNotAccepted() throws Exception {
        saveActive(PASSWORD_72);

        // 对照：账户自己的密码确实能用，所以下面的 400 不是「账户夹具造坏了」。
        login(EMAIL, PASSWORD_72).andExpect(status().isOk());

        // 不校验上限的话，bcrypt 会把这段输入截成同一份 72 字节，这条会变成 200。
        login(EMAIL, PASSWORD_72 + "b")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                .andExpect(header().doesNotExist(HttpHeaders.SET_COOKIE));
    }

    @Test
    @DisplayName("登录邮箱去空白并转小写后查账户：大写变体登入的是同一个账户")
    void loginNormalizesEmailBeforeLookup() throws Exception {
        Account account = saveActive(PASSWORD);

        login(CASE_VARIANT, PASSWORD)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.publicId").value(account.publicId()))
                .andExpect(jsonPath("$.email").value(EMAIL));
    }

    @Test
    @DisplayName("已注册邮箱的大小写变体：建不出第二个账户，也换不出第二张码")
    void caseVariantNeverProducesASecondAccount() throws Exception {
        postRegister(EMAIL, issueCode(), PASSWORD).andExpect(status().isOk());

        // 端点：变体拿不到第二行账户（没有规范化的话，这里会先撞上一条重复键冲突）。
        postRegister(CASE_VARIANT, issueCode(), PASSWORD)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_CODE"));
        assertThat(accounts.findAll().stream().filter(a -> EMAIL.equals(a.email())).count()).isEqualTo(1);

        // 换码路径：`issue` 也是先规范化再查「注册过没有」，所以变体连码都换不出来。
        // 冷却与配额先拆掉，否则这次请求会先被 429 拦在查账户之前。
        String normalized = EmailNormalizer.normalize(EMAIL);
        redis.delete(List.of(registerCodes.cooldownKeyOf(normalized), quota.keyOf(normalized)));
        int sent = outbox.messages().size();
        CaptchaIssue issue = captcha.issue(CAPTCHA_ANSWER);
        keys.add(CAPTCHA_KEY_PREFIX + issue.captchaId());
        registerCodes.issue(CASE_VARIANT, issue.captchaId(), CAPTCHA_ANSWER);

        assertThat(outbox.messages()).hasSize(sent);
        assertThat(redis.hasKey(registerCodes.keyOf(normalized))).isFalse();
    }

    private ResultActions login(String email, String password) throws Exception {
        return mockMvc.perform(MockMvcRequestBuilders.post("/api/auth/login")
                .contentType(MediaType.APPLICATION_JSON)
                .header(HttpHeaders.ORIGIN, ORIGIN)
                .content(objectMapper.writeValueAsString(new LoginRequest(email, password, null, null))));
    }

    private ResultActions postRegister(String email, String code, String password) throws Exception {
        return mockMvc.perform(MockMvcRequestBuilders.post("/api/auth/register")
                .contentType(MediaType.APPLICATION_JSON)
                .header(HttpHeaders.ORIGIN, ORIGIN)
                .content(objectMapper.writeValueAsString(new RegisterRequest(email, code, password, null))));
    }

    /**
     * 账户夹具直接落库，不走注册端点——那样每个用例都要先换图形码、再换注册码，
     * 测的就不再是登录入参了。
     */
    private Account saveActive(String rawPassword) {
        Account saved = accounts.saveAndFlush(
                Account.createActive(Ulid.generate(), EMAIL, hasher.hash(rawPassword), "Gina"));
        accountIdToClean = saved.id();
        return saved;
    }

    /** 取一张可用的注册码：先拆掉冷却与日配额，它们不是本类的被测对象。 */
    private String issueCode() {
        String normalized = EmailNormalizer.normalize(EMAIL);
        redis.delete(List.of(registerCodes.cooldownKeyOf(normalized), quota.keyOf(normalized)));
        CaptchaIssue issue = captcha.issue(CAPTCHA_ANSWER);
        keys.add(CAPTCHA_KEY_PREFIX + issue.captchaId());
        registerCodes.issue(EMAIL, issue.captchaId(), CAPTCHA_ANSWER);
        keys.addAll(codeKeysOf(EMAIL));
        List<OutboxVerificationCodeSender.Message> messages = outbox.messages();
        return messages.get(messages.size() - 1).code();
    }

    private List<String> codeKeysOf(String email) {
        String normalized = EmailNormalizer.normalize(email);
        return List.of(
                registerCodes.keyOf(normalized),
                registerCodes.failKeyOf(normalized),
                registerCodes.cooldownKeyOf(normalized),
                quota.keyOf(normalized));
    }
}
