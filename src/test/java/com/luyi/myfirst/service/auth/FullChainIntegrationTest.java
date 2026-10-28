package com.luyi.myfirst.service.auth;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.luyi.myfirst.common.enums.AccountStatus;
import com.luyi.myfirst.dao.auth.entity.Account;
import com.luyi.myfirst.dao.auth.repository.AccountRepository;
import com.luyi.myfirst.dao.auth.repository.AuthSessionRepository;
import com.luyi.myfirst.service.auth.impl.CaptchaServiceImpl;
import com.luyi.myfirst.service.auth.impl.PasswordResetCodeServiceImpl;
import com.luyi.myfirst.service.auth.impl.RegisterCodeServiceImpl;
import com.luyi.myfirst.service.auth.model.CaptchaIssue;
import com.luyi.myfirst.service.auth.model.VerifiedToken;
import com.luyi.myfirst.service.auth.support.CodePurpose;
import com.luyi.myfirst.service.auth.support.EmailNormalizer;
import com.luyi.myfirst.service.auth.support.EmailQuotaGuard;
import com.luyi.myfirst.service.auth.support.LoginThrottle;
import com.luyi.myfirst.service.auth.support.OutboxVerificationCodeSender;
import com.luyi.myfirst.service.auth.support.TokenVerifier;
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
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.startsWith;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 集成验收（tasks 8.1）：在真实数据库与真实 Redis 上把整条账户生命周期首尾相接地跑一遍。
 *
 * 链路：取图形码 → 换注册码 → 注册（收 cookie） → 登录 → 登出（清 cookie） → 重置密码。
 * 与既有测试的分工：{@code RegisterFlowFullChainTest}（4.7）只走到注册，{@code PasswordResetTest}（7.x）
 * 从既有账户起步只验重置，{@code AccountEndpointsTest}（5.6）从既有账户起步只验 me/登出/注销——
 * 没有一个把「同一条账户从出生到改钥匙」串成一条真实的因果链。本类补的正是这条链：每一步的
 * 产物（cookie、outbox 里的码、账户行、会话行）都被下一步真实消费，任何一步的契约漂移都会在这里断链。
 *
 * 每一步都断言三件事：**响应码**（200/202/204/401 各归其位）、**cookie 属性**（下发时
 * HttpOnly/SameSite=Lax/Path=/，清除时逐项一致再加 Max-Age=0）、**契约一致**（响应体字段与 DTO 逐字对齐、
 * 令牌只走 Set-Cookie 不回显、账户状态迁移只发生在该发生的地方）。
 *
 * 两条「对照组」断言防止假绿：登出后先证注册时那枚 cookie 仍能读 /me（登出只吊销当前令牌，不是全部），
 * 再证重置之后它才失效（重置递增 token version，此前签发的全部令牌一起死）——只断言「重置后 401」的话，
 * 一个「登出就吊销全部」或「谁都不认」的实现同样能过。
 *
 * 「取图形码」的 HTTP 入口离线可用（响应含 id 与 base64 PNG、不含答案），但答案不在响应里，
 * 因此驱动链路只能用 {@code CaptchaServiceImpl.issue(answer)} 这个 test-only 入口铸一张答案已知的图形码。
 * 库内断言靠 {@code @Transactional} 回滚；Redis 不受事务管理，键自己登记、自己删。
 */
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class FullChainIntegrationTest {

    private static final String EMAIL = "fullchain@example.com";
    private static final String PASSWORD = "correct-horse-battery";
    private static final String NEW_PASSWORD = "staple-quantum-battery";
    private static final String DISPLAY_NAME = "Chain Walker";
    private static final String ORIGIN = "http://localhost:3000";
    private static final String CAPTCHA_ANSWER = "ABCD";
    private static final String CAPTCHA_KEY_PREFIX = "wc:captcha:";

    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private CaptchaServiceImpl captcha;
    @Autowired private RegisterCodeServiceImpl registerCodes;
    @Autowired private PasswordResetCodeServiceImpl resetCodes;
    @Autowired private OutboxVerificationCodeSender outbox;
    @Autowired private EmailQuotaGuard quota;
    @Autowired private StringRedisTemplate redis;
    @Autowired private AccountRepository accounts;
    @Autowired private AuthSessionRepository sessions;
    @Autowired private TokenVerifier verifier;
    @Autowired private LoginThrottle throttle;

    @MockitoBean private SourceKeyResolver sourceKey;

    private final List<String> keys = new ArrayList<>();
    private Long accountId;

    @BeforeEach
    void resetState() {
        // 每个用例一个随机来源，登录失败计数互不牵连（本链路末尾会制造一次失败）。
        when(sourceKey.resolve(any())).thenReturn("fullchain-" + UUID.randomUUID());
        outbox.clear();
        accountId = null;
        resetCodeState(EMAIL);
    }

    @AfterEach
    void cleanUp() {
        if (accountId != null) {
            throttle.clearAccountFailures(accountId);
        }
        if (!keys.isEmpty()) {
            redis.delete(keys);
            keys.clear();
        }
    }

    @Test
    @DisplayName("全链路：图形码→注册码→注册→登录→登出→重置，每步响应码/cookie 属性/契约一致")
    void fullChainWalksCaptchaToPasswordResetOnRealDbAndRedis() throws Exception {
        String normalized = EmailNormalizer.normalize(EMAIL);
        registerPersistentKeys(normalized);

        // ── 步骤 1：取图形码。HTTP 入口离线可用（200 + id + base64 PNG，答案不回显）。
        MvcResult captchaEntry = mockMvc.perform(MockMvcRequestBuilders.get("/api/auth/captcha"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.captchaId").isNotEmpty())
                .andExpect(jsonPath("$.image").value(startsWith("data:image/png;base64,")))
                .andReturn();
        String entryBody = captchaEntry.getResponse().getContentAsString(StandardCharsets.UTF_8);
        assertThat(entryBody)
                .as("图形码响应只含 id 与图，答案不出现在响应里")
                .doesNotContain("answer")
                .doesNotContain(CAPTCHA_ANSWER);
        keys.add(CAPTCHA_KEY_PREFIX + objectMapper.readTree(entryBody).get("captchaId").asText());

        // 驱动链路用 test-only 入口铸一张答案已知的图形码（HTTP 响应刻意不含答案）。
        CaptchaIssue issue = captcha.issue(CAPTCHA_ANSWER);
        keys.add(CAPTCHA_KEY_PREFIX + issue.captchaId());

        // ── 步骤 2：换注册码。202、不下发 cookie，明文码只落进程内 outbox（不联网）。
        post("/api/auth/register-code",
                new RegisterCodeRequest(EMAIL, issue.captchaId(), CAPTCHA_ANSWER))
                .andExpect(status().isAccepted())
                .andExpect(header().doesNotExist(HttpHeaders.SET_COOKIE));
        List<OutboxVerificationCodeSender.Message> afterCode = outbox.messages();
        assertThat(afterCode).as("换注册码应投递且只投递一枚").hasSize(1);
        OutboxVerificationCodeSender.Message registerMessage = afterCode.get(0);
        assertThat(registerMessage.email()).isEqualTo(EMAIL);
        assertThat(registerMessage.purpose()).isEqualTo(CodePurpose.REGISTER);
        assertThat(registerMessage.code()).as("注册码是 6 位数字").matches("\\d{6}");

        // ── 步骤 3：注册。200 + 三段响应体 + cookie（HttpOnly/SameSite=Lax/Path=/），当场自动登录。
        MvcResult register = post("/api/auth/register",
                new RegisterRequest(EMAIL, registerMessage.code(), PASSWORD, DISPLAY_NAME))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.publicId").isNotEmpty())
                .andExpect(jsonPath("$.email").value(EMAIL))
                .andExpect(jsonPath("$.displayName").value(DISPLAY_NAME))
                .andExpect(header().string(HttpHeaders.SET_COOKIE, containsString("HttpOnly")))
                .andExpect(header().string(HttpHeaders.SET_COOKIE, containsString("SameSite=Lax")))
                .andExpect(header().string(HttpHeaders.SET_COOKIE, containsString("Path=/")))
                .andReturn();
        Cookie registerCookie = register.getResponse().getCookie(AuthCookie.NAME);
        assertThat(registerCookie).as("注册成功应当场下发认证 cookie").isNotNull();
        String registerToken = registerCookie.getValue();
        String registerBody = register.getResponse().getContentAsString(StandardCharsets.UTF_8);
        assertThat(registerBody)
                .as("令牌只走 Set-Cookie，响应体不含令牌明文，也没有能装下它的字段")
                .doesNotContain(registerToken)
                .doesNotContain("token");

        Account account = accounts.findByEmailAndDeletedAtIsNull(EMAIL).orElseThrow();
        accountId = account.id();
        assertThat(account.status()).as("注册后应为 active").isEqualTo(AccountStatus.ACTIVE);
        assertThat(account.publicId()).hasSize(26);
        VerifiedToken verified = verifier.verify(registerToken).orElseThrow();
        assertThat(verified.account().publicId()).isEqualTo(account.publicId());
        assertThat(sessions.findByJti(verified.jti())).as("注册签发的令牌应有对应会话行").isPresent();

        // ── 步骤 4：登录。200 + 四段响应体（含 expiresAt）+ cookie 属性逐项一致。
        MvcResult login = post("/api/auth/login", new LoginRequest(EMAIL, PASSWORD, null, null))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.publicId").value(account.publicId()))
                .andExpect(jsonPath("$.email").value(EMAIL))
                .andExpect(jsonPath("$.displayName").value(DISPLAY_NAME))
                .andExpect(jsonPath("$.expiresAt").isNotEmpty())
                .andExpect(header().string(HttpHeaders.SET_COOKIE, containsString("HttpOnly")))
                .andExpect(header().string(HttpHeaders.SET_COOKIE, containsString("SameSite=Lax")))
                .andExpect(header().string(HttpHeaders.SET_COOKIE, containsString("Path=/")))
                .andReturn();
        Cookie loginCookie = login.getResponse().getCookie(AuthCookie.NAME);
        assertThat(loginCookie).as("登录成功应下发认证 cookie").isNotNull();
        String loginBody = login.getResponse().getContentAsString(StandardCharsets.UTF_8);
        assertThat(loginBody).doesNotContain(loginCookie.getValue()).doesNotContain("token");

        // ── 步骤 5：登出。204 + cookie 逐项清除（同名、空值、Max-Age=0，属性与下发一致）。
        MvcResult logout = mockMvc.perform(MockMvcRequestBuilders.post("/api/auth/logout")
                        .header(HttpHeaders.ORIGIN, ORIGIN)
                        .cookie(loginCookie))
                .andExpect(status().isNoContent())
                .andReturn();
        String cleared = logout.getResponse().getHeader(HttpHeaders.SET_COOKIE);
        assertThat(cleared)
                .as("清 cookie 与下发时属性逐项一致，否则浏览器把它当成另一个 cookie")
                .contains(AuthCookie.NAME + "=;")
                .contains("Max-Age=0")
                .contains("HttpOnly")
                .contains("SameSite=Lax")
                .contains("Path=/");
        // 登出后旧令牌立即失效。
        mockMvc.perform(MockMvcRequestBuilders.get("/api/auth/me").cookie(loginCookie))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHENTICATED"));
        // 对照组：登出只吊销当前令牌，注册时那枚仍然有效（否则「登出即吊销全部」会冒充通过）。
        mockMvc.perform(MockMvcRequestBuilders.get("/api/auth/me").cookie(registerCookie))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.email").value(EMAIL));

        // ── 步骤 6：重置密码。冷却跨用途共用，先清掉再发重置码。
        redis.delete(registerCodes.cooldownKeyOf(normalized));
        CaptchaIssue resetIssue = captcha.issue(CAPTCHA_ANSWER);
        keys.add(CAPTCHA_KEY_PREFIX + resetIssue.captchaId());
        post("/api/auth/password-reset-code",
                new PasswordResetCodeRequest(EMAIL, resetIssue.captchaId(), CAPTCHA_ANSWER))
                .andExpect(status().isAccepted())
                .andExpect(header().doesNotExist(HttpHeaders.SET_COOKIE));
        List<OutboxVerificationCodeSender.Message> afterReset = outbox.messages();
        assertThat(afterReset).as("重置码应追加投递第二枚").hasSize(2);
        OutboxVerificationCodeSender.Message resetMessage = afterReset.get(1);
        assertThat(resetMessage.purpose()).isEqualTo(CodePurpose.PASSWORD_RESET);
        assertThat(resetMessage.code()).matches("\\d{6}");

        // 提交重置：200 + 空响应体（重置不自动登录，不下发 cookie）。
        post("/api/auth/password-reset",
                new PasswordResetRequest(EMAIL, resetMessage.code(), NEW_PASSWORD))
                .andExpect(status().isOk())
                .andExpect(content().string(""))
                .andExpect(header().doesNotExist(HttpHeaders.SET_COOKIE));
        // 重置不是状态迁移：账户仍 active。
        assertThat(accounts.findById(accountId).orElseThrow().status())
                .as("重置密码不改状态").isEqualTo(AccountStatus.ACTIVE);
        // 重置递增 token version，此前签发的全部令牌（含注册那枚）一起失效。
        mockMvc.perform(MockMvcRequestBuilders.get("/api/auth/me").cookie(registerCookie))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHENTICATED"));
        // 契约核心：旧密码被拒、新密码可立即登录。
        post("/api/auth/login", new LoginRequest(EMAIL, PASSWORD, null, null))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHENTICATED"));
        MvcResult relogin = post("/api/auth/login", new LoginRequest(EMAIL, NEW_PASSWORD, null, null))
                .andExpect(status().isOk())
                .andExpect(header().string(HttpHeaders.SET_COOKIE, containsString("HttpOnly")))
                .andReturn();
        assertThat(relogin.getResponse().getCookie(AuthCookie.NAME))
                .as("新密码登录应下发新 cookie").isNotNull();
    }

    private ResultActions post(String path, Object body) throws Exception {
        return mockMvc.perform(MockMvcRequestBuilders.post(path)
                .header(HttpHeaders.ORIGIN, ORIGIN)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(body)));
    }

    /** 本链路会写到的全部持久键：注册码 / 重置码 / 冷却 / 日配额，登记后在 @AfterEach 一并删。 */
    private void registerPersistentKeys(String normalized) {
        keys.add(registerCodes.keyOf(normalized));
        keys.add(registerCodes.failKeyOf(normalized));
        keys.add(registerCodes.cooldownKeyOf(normalized));
        keys.add(resetCodes.keyOf(normalized));
        keys.add(resetCodes.failKeyOf(normalized));
        keys.add(resetCodes.cooldownKeyOf(normalized));
        keys.add(quota.keyOf(normalized));
    }

    private void resetCodeState(String email) {
        String normalized = EmailNormalizer.normalize(email);
        redis.delete(List.of(
                registerCodes.keyOf(normalized),
                registerCodes.failKeyOf(normalized),
                registerCodes.cooldownKeyOf(normalized),
                resetCodes.keyOf(normalized),
                resetCodes.failKeyOf(normalized),
                resetCodes.cooldownKeyOf(normalized),
                quota.keyOf(normalized)));
    }
}
