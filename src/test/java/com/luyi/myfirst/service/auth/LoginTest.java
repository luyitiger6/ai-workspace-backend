package com.luyi.myfirst.service.auth;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.luyi.myfirst.common.enums.AccountStatus;
import com.luyi.myfirst.common.id.Ulid;
import com.luyi.myfirst.common.security.PasswordHasher;
import com.luyi.myfirst.config.AuthProperties;
import com.luyi.myfirst.dao.auth.entity.Account;
import com.luyi.myfirst.dao.auth.repository.AccountRepository;
import com.luyi.myfirst.dao.auth.repository.AuthSessionRepository;
import com.luyi.myfirst.service.auth.impl.CaptchaServiceImpl;
import com.luyi.myfirst.service.auth.model.CaptchaIssue;
import com.luyi.myfirst.service.auth.model.VerifiedToken;
import com.luyi.myfirst.service.auth.support.LoginThrottle;
import com.luyi.myfirst.service.auth.support.TokenVerifier;
import com.luyi.myfirst.web.auth.AuthCookie;
import com.luyi.myfirst.web.auth.dto.LoginRequest;
import com.luyi.myfirst.web.auth.dto.LoginResponse;
import com.luyi.myfirst.web.auth.interceptor.SourceKeyResolver;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;
import org.springframework.transaction.annotation.Transactional;

import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 登录端点的契约（tasks 5.3 / 5.4）：先限流、再凭证、后状态；三态的响应码由状态唯一决定。
 *
 * 走 HTTP 而不是直接调 service：三态里有两条是「响应不可区分」（已注销 vs 密码错、
 * 邮箱不存在 vs 密码错），只有穿过 GlobalExceptionHandler 与真实序列化才算数。
 *
 * 来源键换成每个用例一个随机值（{@link SourceKeyResolver} 被换掉）：真正的来源认定
 * 属 5.9，而本类每个用例都要制造失败，用一个固定的来源键会让本类自己把自己限流掉，
 * 还会连带影响到同一个 Redis 里跑的其他用例。
 *
 * 库里断言靠 {@code @Transactional} 回滚；账户夹具直接落库，不走注册端点——
 * 那样每个用例都要先取图形码、换注册码，测的就不再是登录了。
 */
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class LoginTest {

    private static final String EMAIL = "dana@example.com";
    private static final String ABSENT_EMAIL = "nobody@example.com";
    private static final String PASSWORD = "correct-horse-battery";
    private static final String WRONG_PASSWORD = "correct-horse-battery-x";
    private static final String ORIGIN = "http://localhost:3000";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private AccountRepository accounts;

    @Autowired
    private AuthSessionRepository sessions;

    @Autowired
    private PasswordHasher hasher;

    @Autowired
    private TokenVerifier verifier;

    @Autowired
    private AuthProperties properties;

    @Autowired
    private LoginThrottle throttle;

    @Autowired
    private CaptchaServiceImpl captcha;

    @MockitoBean
    private SourceKeyResolver sourceKey;

    /** 每个用例一个来源，避免本类的失败计数互相牵连（TTL 到期后自行消失）。 */
    private String source;

    /** 账户计数存在共享缓存里，30 天 TTL；数据库回滚救不了它，只能手动清。 */
    private Long accountIdToClean;

    @BeforeEach
    void isolateSource() {
        source = "login-test-" + UUID.randomUUID();
        when(sourceKey.resolve(any())).thenReturn(source);
    }

    @AfterEach
    void cleanAccountCounter() {
        if (accountIdToClean != null) {
            throttle.clearAccountFailures(accountIdToClean);
        }
    }

    @Test
    @DisplayName("active 账户登录成功：200，令牌只在 Set-Cookie，响应体的过期时间与 cookie 同源")
    void activeAccountLogsIn() throws Exception {
        Account account = saveAccount(AccountStatus.ACTIVE);

        MvcResult result = login(EMAIL, PASSWORD)
                .andExpect(status().isOk())
                .andReturn();

        String body = bodyOf(result);
        String setCookie = result.getResponse().getHeader(HttpHeaders.SET_COOKIE);
        String token = result.getResponse().getCookie(AuthCookie.NAME).getValue();

        // cookie 的属性一次写齐：少一项，浏览器就当它是另一个 cookie。
        assertThat(setCookie)
                .contains(AuthCookie.NAME + "=")
                .contains("HttpOnly")
                .contains("SameSite=Lax")
                .contains("Path=/")
                .contains("Max-Age=" + properties.sessionTtl().toSeconds());

        // 令牌不出现在响应体：前端拿不到字符串，也就没机会把它存进 localStorage。
        assertThat(body)
                .doesNotContain(token)
                .contains(account.publicId())
                .contains(EMAIL);

        // 过期时间与配置、与 cookie 的 Max-Age 同源，不是各算一遍。
        Instant expiresAt = objectMapper.readValue(body, LoginResponse.class).expiresAt();
        assertThat(Duration.between(Instant.now(), expiresAt))
                .isBetween(properties.sessionTtl().minusSeconds(5), properties.sessionTtl());

        // 令牌可用，且会话行确实落了库：签发与登记必须是同一次处理。
        VerifiedToken verified = verifier.verify(token).orElseThrow();
        assertThat(verified.jti()).isNotBlank();
        assertThat(sessions.findByJti(verified.jti()))
                .get()
                .extracting(session -> session.accountId())
                .isEqualTo(account.id());
    }

    @Test
    @DisplayName("email_unverified 账户凭证正确也返回 403，且不签发令牌、状态不变")
    void unverifiedAccountIsRejectedWith403() throws Exception {
        saveAccount(AccountStatus.EMAIL_UNVERIFIED);

        MvcResult result = login(EMAIL, PASSWORD)
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("EMAIL_UNVERIFIED"))
                .andReturn();

        assertThat(result.getResponse().getHeader(HttpHeaders.SET_COOKIE)).isNull();
        assertThat(statusOf(EMAIL)).isEqualTo(AccountStatus.EMAIL_UNVERIFIED);
    }

    @Test
    @DisplayName("已注销账户凭证正确也返回 401，且不签发令牌")
    void deletedAccountIsRejectedWith401() throws Exception {
        saveAccount(AccountStatus.DELETED);

        MvcResult result = login(EMAIL, PASSWORD)
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHENTICATED"))
                .andReturn();

        assertThat(result.getResponse().getHeader(HttpHeaders.SET_COOKIE)).isNull();
    }

    @Test
    @DisplayName("邮箱不存在与密码错误逐字同形——先凭证后状态的直接证据")
    void unknownEmailAndWrongPasswordAreIndistinguishable() throws Exception {
        saveAccount(AccountStatus.ACTIVE);

        MvcResult absent = login(ABSENT_EMAIL, PASSWORD)
                .andExpect(status().isUnauthorized())
                .andReturn();
        MvcResult wrongPassword = login(EMAIL, WRONG_PASSWORD)
                .andExpect(status().isUnauthorized())
                .andReturn();

        // 不是「状态码相同即可」：少一个字段、多一句提示，结论就写进响应里了。
        assertThat(bodyOf(absent)).isEqualTo(bodyOf(wrongPassword));
        assertThat(absent.getResponse().getHeader(HttpHeaders.SET_COOKIE)).isNull();
        assertThat(wrongPassword.getResponse().getHeader(HttpHeaders.SET_COOKIE)).isNull();
    }

    @Test
    @DisplayName("未验证账户密码错误：401 而不是 403——403 只在密码也对时才可能出现")
    void unverifiedAccountWithWrongPasswordIsRejectedAsUnauthenticated() throws Exception {
        Account account = saveAccount(AccountStatus.EMAIL_UNVERIFIED);

        MvcResult result = login(EMAIL, WRONG_PASSWORD)
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHENTICATED"))
                .andReturn();

        // 同一个账户、同一个密码换对就变 403：两次响应的差别只可能来自密码，
        // 不可能是「这个邮箱存不存在」或「验没验证」。
        login(EMAIL, PASSWORD).andExpect(status().isForbidden());

        assertThat(result.getResponse().getHeader(HttpHeaders.SET_COOKIE)).isNull();
        assertThat(account.status()).isEqualTo(AccountStatus.EMAIL_UNVERIFIED);
    }

    @Test
    @DisplayName("账户连续失败达阈值后，密码正确也要先过图形码；过了码才签发令牌并清零计数")
    void captchaIsRequiredBeforePasswordComparisonOnceThresholdReached() throws Exception {
        Account account = saveAccount(AccountStatus.ACTIVE);
        for (int i = 0; i < properties.login().failThreshold(); i++) {
            throttle.recordFailure(account.id(), source);
        }

        // 密码是对的，但没带码：400 而非 200——证明码的判定在密码比对之前。
        login(EMAIL, PASSWORD)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("CAPTCHA_INVALID"))
                .andExpect(result -> assertThat(
                        result.getResponse().getHeader(HttpHeaders.SET_COOKIE)).isNull());

        CaptchaIssue issue = captcha.issue("ABCD");
        login(EMAIL, PASSWORD, issue.captchaId(), "ABCD")
                .andExpect(status().isOk());

        assertThat(throttle.requiresCaptcha(account.id())).isFalse();
    }

    private ResultActions login(String email, String password) throws Exception {
        return login(email, password, null, null);
    }

    private ResultActions login(String email, String password, String captchaId, String captchaAnswer)
            throws Exception {
        return mockMvc.perform(MockMvcRequestBuilders.post("/api/auth/login")
                .contentType(MediaType.APPLICATION_JSON)
                .header(HttpHeaders.ORIGIN, ORIGIN)
                .content(objectMapper.writeValueAsString(
                        new LoginRequest(email, password, captchaId, captchaAnswer))));
    }

    private static String bodyOf(MvcResult result) throws Exception {
        return result.getResponse().getContentAsString(StandardCharsets.UTF_8);
    }

    private AccountStatus statusOf(String email) {
        return accounts.findByEmailAndDeletedAtIsNull(email).orElseThrow().status();
    }

    private Account saveAccount(AccountStatus status) {
        Account account = Account.createActive(Ulid.generate(), EMAIL, hasher.hash(PASSWORD), "Dana");
        if (status == AccountStatus.EMAIL_UNVERIFIED) {
            forceStatus(account, status);
        } else if (status == AccountStatus.DELETED) {
            account.delete(Instant.now());
        }
        Account saved = accounts.saveAndFlush(account);
        accountIdToClean = saved.id();
        return saved;
    }

    /**
     * email_unverified 只能由测试夹具造出（spec 明确该状态生产不可达、也无出口）。
     * 反射放在测试里，生产代码不暴露置入入口。
     */
    private static void forceStatus(Account account, AccountStatus status) {
        try {
            Field field = Account.class.getDeclaredField("status");
            field.setAccessible(true);
            field.set(account, status);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("无法构造状态夹具", e);
        }
    }
}
