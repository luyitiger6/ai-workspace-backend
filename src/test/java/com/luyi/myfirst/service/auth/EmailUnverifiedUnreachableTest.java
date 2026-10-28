package com.luyi.myfirst.service.auth;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.luyi.myfirst.common.enums.AccountStatus;
import com.luyi.myfirst.common.id.Ulid;
import com.luyi.myfirst.common.security.PasswordHasher;
import com.luyi.myfirst.dao.auth.entity.Account;
import com.luyi.myfirst.dao.auth.repository.AccountRepository;
import com.luyi.myfirst.service.auth.impl.CaptchaServiceImpl;
import com.luyi.myfirst.service.auth.impl.PasswordResetCodeServiceImpl;
import com.luyi.myfirst.service.auth.impl.RegisterCodeServiceImpl;
import com.luyi.myfirst.service.auth.model.CaptchaIssue;
import com.luyi.myfirst.service.auth.support.EmailNormalizer;
import com.luyi.myfirst.service.auth.support.EmailQuotaGuard;
import com.luyi.myfirst.service.auth.support.LoginThrottle;
import com.luyi.myfirst.service.auth.support.OutboxVerificationCodeSender;
import com.luyi.myfirst.web.auth.AuthCookie;
import com.luyi.myfirst.web.auth.dto.LoginRequest;
import com.luyi.myfirst.web.auth.dto.PasswordResetCodeRequest;
import com.luyi.myfirst.web.auth.dto.PasswordResetRequest;
import com.luyi.myfirst.web.auth.dto.RegisterCodeRequest;
import com.luyi.myfirst.web.auth.dto.RegisterRequest;
import com.luyi.myfirst.web.auth.interceptor.SourceKeyResolver;
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
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;

import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * `email_unverified` 在本变更内没有任何生产入口（tasks 2.6）。
 *
 * 这个状态是保留值：既无入口也无出口（登录拿不到令牌，故注销边不可达）。spec 用两条断言把它钉住：
 *
 * 1. 遍历 `/api/auth` 端点清单，用合法输入把九个端点各驱动一次（一条真实账户从注册到注销的完整
 *    生命周期），每一步之后都断言库内没有任何账户被置入 `email_unverified`；并断言「我驱动过的端点
 *    集合」恰等于从真实路由表枚举出的端点集合——将来多挂一个端点，这条立刻红，逼迫补进本遍历，
 *    否则新端点可能就是那个偷偷置入该状态的入口。
 * 2. 用测试夹具（反射）造出 `email_unverified`，验证登录返回 `403` + `EMAIL_UNVERIFIED` 且状态不变。
 *
 * 与既有覆盖的关系：{@code LoginTest} 已证登录 403；本类把它与「无端点能置入该状态」这条正向
 * 断言放在一起，构成 2.6 的完整验收——单有 403 不能说明这个状态在生产不可达。
 */
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class EmailUnverifiedUnreachableTest {

    private static final String EMAIL = "sweep@example.com";
    private static final String FIXTURE_EMAIL = "fixture-unverified@example.com";
    private static final String PASSWORD = "correct-horse-battery";
    private static final String NEW_PASSWORD = "staple-quantum-battery";
    private static final String ORIGIN = "http://localhost:3000";
    private static final String CAPTCHA_ANSWER = "ABCD";
    private static final String CAPTCHA_KEY_PREFIX = "wc:captcha:";

    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private AccountRepository accounts;
    @Autowired private PasswordHasher hasher;
    @Autowired private CaptchaServiceImpl captcha;
    @Autowired private RegisterCodeServiceImpl registerCodes;
    @Autowired private PasswordResetCodeServiceImpl resetCodes;
    @Autowired private OutboxVerificationCodeSender outbox;
    @Autowired private EmailQuotaGuard quota;
    @Autowired private StringRedisTemplate redis;
    @Autowired private LoginThrottle throttle;
    @Autowired private RequestMappingHandlerMapping handlerMapping;

    @MockitoBean private SourceKeyResolver sourceKey;

    private final List<String> keys = new ArrayList<>();
    private final Set<String> swept = new TreeSet<>();
    private Long sweptAccountId;

    @BeforeEach
    void resetState() {
        when(sourceKey.resolve(any())).thenReturn("unverified-sweep-" + UUID.randomUUID());
        outbox.clear();
        swept.clear();
        sweptAccountId = null;
        resetCodeState(EMAIL);
    }

    @AfterEach
    void cleanUp() {
        if (sweptAccountId != null) {
            throttle.clearAccountFailures(sweptAccountId);
        }
        if (!keys.isEmpty()) {
            redis.delete(keys);
            keys.clear();
        }
    }

    @Test
    @DisplayName("遍历九个端点跑完整生命周期：全程没有任何账户被置入 email_unverified")
    void noEndpointEverPutsAnAccountIntoEmailUnverified() throws Exception {
        // ① GET /api/auth/captcha —— 链路入口，离线可用。
        mockMvc.perform(MockMvcRequestBuilders.get("/api/auth/captcha"))
                .andExpect(status().isOk());
        swept.add("GET /api/auth/captcha");
        assertNoAccountIsEmailUnverified("取图形码");

        // ② POST /api/auth/register-code —— 消费图形码，把注册码投递进 outbox。
        String registerCode = requestRegisterCode(EMAIL);
        swept.add("POST /api/auth/register-code");
        assertNoAccountIsEmailUnverified("换注册码");

        // ③ POST /api/auth/register —— 建 active 账户并当场下发 cookie。
        MvcResult register = post("/api/auth/register",
                objectMapper.writeValueAsString(new RegisterRequest(EMAIL, registerCode, PASSWORD, "Sweep")))
                .andExpect(status().isOk())
                .andReturn();
        swept.add("POST /api/auth/register");
        Account afterRegister = accounts.findByEmailAndDeletedAtIsNull(EMAIL).orElseThrow();
        sweptAccountId = afterRegister.id();
        assertThat(afterRegister.status()).as("注册后应为 active").isEqualTo(AccountStatus.ACTIVE);
        assertNoAccountIsEmailUnverified("注册");

        // ④ POST /api/auth/login —— 凭证正确、状态 active，返回 200 与 cookie。
        Cookie loginCookie = login(EMAIL, PASSWORD);
        swept.add("POST /api/auth/login");
        assertNoAccountIsEmailUnverified("登录");

        // ⑤ GET /api/auth/me —— 持有效令牌读自己。
        mockMvc.perform(MockMvcRequestBuilders.get("/api/auth/me").cookie(loginCookie))
                .andExpect(status().isOk());
        swept.add("GET /api/auth/me");
        assertNoAccountIsEmailUnverified("读当前用户");

        // ⑥ POST /api/auth/password-reset-code —— 冷却跨用途共用，先清掉再发重置码。
        clearCooldown(EMAIL);
        String resetCode = requestResetCode(EMAIL);
        swept.add("POST /api/auth/password-reset-code");
        assertNoAccountIsEmailUnverified("换重置码");

        // ⑦ POST /api/auth/password-reset —— 改密码并递增令牌版本（不改状态）。
        post("/api/auth/password-reset",
                objectMapper.writeValueAsString(new PasswordResetRequest(EMAIL, resetCode, NEW_PASSWORD)))
                .andExpect(status().isOk());
        swept.add("POST /api/auth/password-reset");
        assertThat(accounts.findById(sweptAccountId).orElseThrow().status())
                .as("重置密码不是状态迁移").isEqualTo(AccountStatus.ACTIVE);
        assertNoAccountIsEmailUnverified("重置密码");

        // ⑧ POST /api/auth/logout —— 重置后旧令牌已失效，用新密码重新登录再登出。
        Cookie afterReset = loginWith(EMAIL, NEW_PASSWORD);
        mockMvc.perform(MockMvcRequestBuilders.post("/api/auth/logout")
                        .header(HttpHeaders.ORIGIN, ORIGIN)
                        .cookie(afterReset))
                .andExpect(status().isNoContent());
        swept.add("POST /api/auth/logout");
        assertNoAccountIsEmailUnverified("登出");

        // ⑨ DELETE /api/auth/account —— 注销：active -> deleted，这是唯一的状态迁移。
        Cookie forDelete = loginWith(EMAIL, NEW_PASSWORD);
        mockMvc.perform(MockMvcRequestBuilders.delete("/api/auth/account")
                        .header(HttpHeaders.ORIGIN, ORIGIN)
                        .cookie(forDelete))
                .andExpect(status().isNoContent());
        swept.add("DELETE /api/auth/account");
        assertThat(accounts.findById(sweptAccountId).orElseThrow().status())
                .as("注销后应为 deleted").isEqualTo(AccountStatus.DELETED);
        assertNoAccountIsEmailUnverified("注销");

        // 遍历必须覆盖真实路由表里的每一个 /api/auth 端点：多挂一个就会红，逼迫补进本遍历。
        assertThat(swept)
                .as("驱动的端点集合应恰等于真实路由表枚举出的端点集合")
                .containsExactlyInAnyOrderElementsOf(authEndpoints());
    }

    @Test
    @DisplayName("夹具造出 email_unverified：登录 403 + EMAIL_UNVERIFIED，且状态不被改写")
    void fixtureCreatedEmailUnverifiedLogsInAs403AndStaysUnchanged() throws Exception {
        Account account = accounts.saveAndFlush(
                Account.createActive(Ulid.generate(), FIXTURE_EMAIL, hasher.hash(PASSWORD), "Fixture"));
        forceStatus(account, AccountStatus.EMAIL_UNVERIFIED);
        accounts.saveAndFlush(account);
        Long id = account.id();

        mockMvc.perform(MockMvcRequestBuilders.post("/api/auth/login")
                        .header(HttpHeaders.ORIGIN, ORIGIN)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                new LoginRequest(FIXTURE_EMAIL, PASSWORD, null, null))))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("EMAIL_UNVERIFIED"))
                .andExpect(header().doesNotExist(HttpHeaders.SET_COOKIE));

        Account reloaded = accounts.findById(id).orElseThrow();
        assertThat(reloaded.status())
                .as("登录被拒不该改写状态")
                .isEqualTo(AccountStatus.EMAIL_UNVERIFIED);
    }

    private void assertNoAccountIsEmailUnverified(String step) {
        boolean any = accounts.findAll().stream()
                .anyMatch(a -> a.status() == AccountStatus.EMAIL_UNVERIFIED);
        assertThat(any).as("步骤[%s]后不应有任何账户处于 email_unverified", step).isFalse();
    }

    /** `/api/auth` 下的全部映射，形如 `GET /api/auth/captcha`（与 SimulatedSenderGuardTest 同口径）。 */
    private Set<String> authEndpoints() {
        Set<String> endpoints = new TreeSet<>();
        handlerMapping.getHandlerMethods().forEach((info, handler) -> {
            Set<RequestMethod> methods = info.getMethodsCondition().getMethods();
            for (String pattern : info.getPatternValues()) {
                if (!pattern.startsWith("/api/auth")) {
                    continue;
                }
                if (methods.isEmpty()) {
                    endpoints.add("ANY " + pattern);
                    continue;
                }
                for (RequestMethod method : methods) {
                    endpoints.add(method.name() + " " + pattern);
                }
            }
        });
        return endpoints;
    }

    private String requestRegisterCode(String email) throws Exception {
        CaptchaIssue issue = captchaOf();
        post("/api/auth/register-code", objectMapper.writeValueAsString(
                new RegisterCodeRequest(email, issue.captchaId(), CAPTCHA_ANSWER)))
                .andExpect(status().isAccepted());
        return latestOutboxCode();
    }

    private String requestResetCode(String email) throws Exception {
        CaptchaIssue issue = captchaOf();
        post("/api/auth/password-reset-code", objectMapper.writeValueAsString(
                new PasswordResetCodeRequest(email, issue.captchaId(), CAPTCHA_ANSWER)))
                .andExpect(status().isAccepted());
        return latestOutboxCode();
    }

    private String latestOutboxCode() {
        List<OutboxVerificationCodeSender.Message> messages = outbox.messages();
        assertThat(messages).isNotEmpty();
        return messages.get(messages.size() - 1).code();
    }

    private Cookie login(String email, String password) throws Exception {
        return loginWith(email, password);
    }

    private Cookie loginWith(String email, String password) throws Exception {
        MvcResult result = post("/api/auth/login", objectMapper.writeValueAsString(
                new LoginRequest(email, password, null, null)))
                .andExpect(status().isOk())
                .andReturn();
        Cookie cookie = result.getResponse().getCookie(AuthCookie.NAME);
        assertThat(cookie).as("登录成功应下发认证 cookie").isNotNull();
        return cookie;
    }

    private org.springframework.test.web.servlet.ResultActions post(String path, String body) throws Exception {
        return mockMvc.perform(MockMvcRequestBuilders.post(path)
                .header(HttpHeaders.ORIGIN, ORIGIN)
                .contentType(MediaType.APPLICATION_JSON)
                .content(body));
    }

    private CaptchaIssue captchaOf() {
        CaptchaIssue issue = captcha.issue(CAPTCHA_ANSWER);
        keys.add(CAPTCHA_KEY_PREFIX + issue.captchaId());
        return issue;
    }

    private void clearCooldown(String email) {
        redis.delete(registerCodes.cooldownKeyOf(EmailNormalizer.normalize(email)));
    }

    private void resetCodeState(String email) {
        String normalized = EmailNormalizer.normalize(email);
        redis.delete(List.of(
                registerCodes.keyOf(normalized),
                registerCodes.failKeyOf(normalized),
                registerCodes.cooldownKeyOf(normalized),
                resetCodes.keyOf(normalized),
                resetCodes.failKeyOf(normalized),
                quota.keyOf(normalized)));
    }

    /** email_unverified 只能由测试夹具造出（spec 明确该状态生产不可达）。 */
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
