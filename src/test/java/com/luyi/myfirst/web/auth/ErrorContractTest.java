package com.luyi.myfirst.web.auth;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.luyi.myfirst.common.enums.AccountStatus;
import com.luyi.myfirst.common.exception.AuthErrorEnum;
import com.luyi.myfirst.common.id.Ulid;
import com.luyi.myfirst.common.security.PasswordHasher;
import com.luyi.myfirst.config.AuthProperties;
import com.luyi.myfirst.dao.auth.entity.Account;
import com.luyi.myfirst.dao.auth.repository.AccountRepository;
import com.luyi.myfirst.service.auth.support.LoginThrottle;
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
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;
import org.springframework.transaction.annotation.Transactional;

import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 统一错误结构与固定错误码集合（tasks 5.7）。
 *
 * 「错误码取自固定集合」有两种证法：遍历枚举自证，或者让每个码真的从端点上出来。
 * 前者是循环论证——枚举里多一个值，那条断言跟着一起变绿；后者会在多出第八个码时
 * 因为「这条码没有任何端点产出」而留下一个缺口，也会在某个码被抽掉出口时立刻红。
 * 本类走后者：七个码各有一条用例，每条都断言状态码、错误码与响应体的字段集合。
 *
 * 响应体的字段集合也是断言对象：`{code, message}` 之外的任何键都是新增契约，
 * 而凭证回显（邮箱、密码、哈希）连「值」都不该出现——只查字段名挡不住 message
 * 里被拼进去的原文。
 */
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class ErrorContractTest {

    private static final String EMAIL = "frank@example.com";
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
    private PasswordHasher hasher;

    @Autowired
    private LoginThrottle throttle;

    @Autowired
    private AuthProperties properties;

    /** 每个用例一个来源：本类要主动把额度灌满，不能借用真实单桶去牵连其他类。 */
    @MockitoBean
    private SourceKeyResolver sourceKey;

    private String source;

    private final List<Long> accountIdsToClean = new ArrayList<>();

    @BeforeEach
    void isolateSource() {
        source = "error-contract-" + UUID.randomUUID();
        when(sourceKey.resolve(any())).thenReturn(source);
    }

    @AfterEach
    void cleanAccountCounters() {
        accountIdsToClean.forEach(throttle::clearAccountFailures);
        accountIdsToClean.clear();
    }

    @Test
    @DisplayName("错误码恰为七个，且每个码的状态码与 spec 的固定集合逐项一致")
    void errorCodesAreExactlyTheSevenFromTheSpec() {
        // 期望表直接从 spec「统一错误结构」抄下来：这是唯一一处把 spec 的字符串
        // 与枚举对照的地方。新增第八个码、或把某个码的 HTTP 状态改掉，这里立刻红。
        Map<String, HttpStatus> expected = Map.of(
                "UNAUTHENTICATED", HttpStatus.UNAUTHORIZED,
                "EMAIL_UNVERIFIED", HttpStatus.FORBIDDEN,
                "CROSS_SITE_REJECTED", HttpStatus.FORBIDDEN,
                "INVALID_CODE", HttpStatus.BAD_REQUEST,
                "CAPTCHA_INVALID", HttpStatus.BAD_REQUEST,
                "RATE_LIMITED", HttpStatus.TOO_MANY_REQUESTS,
                "VALIDATION_FAILED", HttpStatus.BAD_REQUEST);

        assertThat(AuthErrorEnum.values()).hasSize(expected.size());
        assertThat(Arrays.stream(AuthErrorEnum.values())
                .collect(Collectors.toMap(AuthErrorEnum::getErrorCode, AuthErrorEnum::getHttpStatus)))
                .containsExactlyInAnyOrderEntriesOf(expected);

        // 两个 403 必须能被区分——客户端按错误码分流，不能按状态码猜。
        assertThat(AuthErrorEnum.EMAIL_UNVERIFIED.getErrorCode())
                .isNotEqualTo(AuthErrorEnum.CROSS_SITE_REJECTED.getErrorCode());

        // 提示文案非空：空文案会退化成「客户端只能看错误码」，而这正是 spec 要避免的。
        assertThat(Arrays.stream(AuthErrorEnum.values()))
                .allSatisfy(error -> assertThat(error.getErrorMsg()).isNotBlank());
    }

    @Test
    @DisplayName("UNAUTHENTICATED：无令牌访问受保护资源")
    void unauthenticatedOnMissingToken() throws Exception {
        assertError(mockMvc.perform(MockMvcRequestBuilders.get("/api/auth/me")).andReturn(),
                HttpStatus.UNAUTHORIZED, "UNAUTHENTICATED");
    }

    @Test
    @DisplayName("EMAIL_UNVERIFIED：凭证正确但状态不是 active")
    void emailUnverifiedOnUnverifiedAccount() throws Exception {
        Account account = saveAccount(EMAIL, AccountStatus.EMAIL_UNVERIFIED);

        MvcResult result = assertError(login(EMAIL, PASSWORD).andReturn(),
                HttpStatus.FORBIDDEN, "EMAIL_UNVERIFIED", PASSWORD);

        // 状态不该被这次拒绝改动。
        assertThat(account.status()).isEqualTo(AccountStatus.EMAIL_UNVERIFIED);
        assertThat(result.getResponse().getHeader(HttpHeaders.SET_COOKIE)).isNull();
    }

    @Test
    @DisplayName("CROSS_SITE_REJECTED：来源校验先于登录判定，403 而不是 401")
    void crossSiteWriteIsRejectedBeforeAuthentication() throws Exception {
        // 不带 cookie：若先判认证，这里会是 401。得到 CROSS_SITE_REJECTED 就证明
        // 「未通过来源校验的请求不再进行凭证与账户状态判定」这一句是真的。
        assertError(mockMvc.perform(MockMvcRequestBuilders.post("/api/auth/logout")
                        .header(HttpHeaders.ORIGIN, "http://evil.example"))
                .andReturn(), HttpStatus.FORBIDDEN, "CROSS_SITE_REJECTED");
    }

    @Test
    @DisplayName("INVALID_CODE：注册码错误")
    void invalidCodeOnWrongRegistrationCode() throws Exception {
        String body = objectMapper.writeValueAsString(new RegisterRequest(
                "newcomer@example.com", "000000", PASSWORD, null));

        assertError(post("/api/auth/register", body).andReturn(),
                HttpStatus.BAD_REQUEST, "INVALID_CODE", PASSWORD);
    }

    @Test
    @DisplayName("CAPTCHA_INVALID：账户达失败阈值后，密码正确也先要图形码")
    void captchaInvalidOnceThresholdReached() throws Exception {
        Account account = saveAccount(EMAIL, AccountStatus.ACTIVE);
        for (int i = 0; i < properties.login().failThreshold(); i++) {
            throttle.recordFailure(account.id(), source);
        }

        assertError(login(EMAIL, PASSWORD).andReturn(),
                HttpStatus.BAD_REQUEST, "CAPTCHA_INVALID", PASSWORD);
    }

    @Test
    @DisplayName("RATE_LIMITED：429 带 Retry-After，且秒数可被客户端直接读数")
    void rateLimitedCarriesRetryAfterHeader() throws Exception {
        saveAccount(EMAIL, AccountStatus.ACTIVE);
        for (int i = 0; i < properties.login().sourceMax(); i++) {
            throttle.recordSourceFailure(source);
        }

        MvcResult result = login(EMAIL, PASSWORD)
                .andExpect(header().exists("Retry-After"))
                .andReturn();

        assertError(result, HttpStatus.TOO_MANY_REQUESTS, "RATE_LIMITED");

        // 头值必须是正整数秒：客户端拿它直接显示「还剩 N 秒」，不需要再发一次请求。
        String retryAfter = result.getResponse().getHeader("Retry-After");
        assertThat(Long.parseLong(retryAfter))
                .isPositive()
                .isEqualTo(properties.login().retryAfter().toSeconds());
    }

    @Test
    @DisplayName("VALIDATION_FAILED：空邮箱在查账户之前被拒")
    void validationFailedOnBlankEmail() throws Exception {
        assertError(login("   ", PASSWORD).andReturn(), HttpStatus.BAD_REQUEST, "VALIDATION_FAILED");
    }

    @Test
    @DisplayName("已注销账户与凭证错误逐字段相等：不是「状态码相同」就算过关")
    void deletedAccountAndWrongPasswordAreFieldByFieldEqual() throws Exception {
        saveAccount("gone@example.com", AccountStatus.DELETED);
        saveAccount("still-here@example.com", AccountStatus.ACTIVE);

        MvcResult deleted = assertError(login("gone@example.com", PASSWORD).andReturn(),
                HttpStatus.UNAUTHORIZED, "UNAUTHENTICATED");
        MvcResult wrongPassword = assertError(login("still-here@example.com", WRONG_PASSWORD).andReturn(),
                HttpStatus.UNAUTHORIZED, "UNAUTHENTICATED");

        // 先各自过一遍字段集合断言再比：只比「两次相等」的话，两边同时多一个字段也照样绿。
        // 解析成 JSON 树再比：字段顺序不该影响结论，但少一个字段、多一个字都必须暴露。
        assertThat(jsonOf(deleted)).isEqualTo(jsonOf(wrongPassword));

        String deletedBody = deleted.getResponse().getContentAsString(StandardCharsets.UTF_8);
        assertThat(deletedBody)
                .doesNotContain("gone@example.com")
                .doesNotContain(WRONG_PASSWORD);
    }

    /** 断言错误响应的状态码、错误码与字段集合，并检查值里没有回显调用方提交的东西。 */
    private MvcResult assertError(MvcResult result,
                                  HttpStatus expectedStatus,
                                  String expectedCode,
                                  String... mustNotContain) throws Exception {
        status().is(expectedStatus.value()).match(result);
        jsonPath("$.code").value(expectedCode).match(result);

        JsonNode body = jsonOf(result);
        List<String> fields = new ArrayList<>();
        body.fieldNames().forEachRemaining(fields::add);
        assertThat(fields)
                .as("错误响应只装错误码与提示，状态码在 HTTP 层、Retry-After 在响应头")
                .containsExactlyInAnyOrder("code", "message");
        assertThat(body.get("message").asText()).isNotBlank();

        String raw = result.getResponse().getContentAsString(StandardCharsets.UTF_8);
        for (String forbidden : mustNotContain) {
            assertThat(raw)
                    .as("错误响应不得回显调用方提交的凭证或原文")
                    .doesNotContain(forbidden);
        }
        return result;
    }

    private JsonNode jsonOf(MvcResult result) throws Exception {
        return objectMapper.readTree(result.getResponse().getContentAsString(StandardCharsets.UTF_8));
    }

    private ResultActions login(String email, String password) throws Exception {
        String body = objectMapper.writeValueAsString(new LoginRequest(email, password, null, null));
        return post("/api/auth/login", body);
    }

    private ResultActions post(String path, String body) throws Exception {
        return mockMvc.perform(MockMvcRequestBuilders.post(path)
                .header(HttpHeaders.ORIGIN, ORIGIN)
                .contentType(MediaType.APPLICATION_JSON)
                .content(body));
    }

    private Account saveAccount(String email, AccountStatus status) {
        Account account = Account.createActive(Ulid.generate(), email, hasher.hash(PASSWORD), "Frank");
        if (status == AccountStatus.EMAIL_UNVERIFIED) {
            forceStatus(account, status);
        } else if (status == AccountStatus.DELETED) {
            account.delete(Instant.now());
        }
        Account saved = accounts.saveAndFlush(account);
        accountIdsToClean.add(saved.id());
        return saved;
    }

    /** 与 LoginTest 同一夹具：email_unverified 只能由测试造出（spec 明确无生产入口）。 */
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
