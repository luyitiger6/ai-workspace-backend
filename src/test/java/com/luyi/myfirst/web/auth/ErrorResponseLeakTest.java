package com.luyi.myfirst.web.auth;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.luyi.myfirst.common.id.Ulid;
import com.luyi.myfirst.common.security.PasswordHasher;
import com.luyi.myfirst.dao.auth.entity.Account;
import com.luyi.myfirst.dao.auth.repository.AccountRepository;
import com.luyi.myfirst.service.auth.CaptchaService;
import com.luyi.myfirst.service.auth.support.LoginThrottle;
import com.luyi.myfirst.web.auth.dto.LoginRequest;
import com.luyi.myfirst.web.auth.interceptor.SourceKeyResolver;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

/**
 * 错误响应不得泄漏内部细节（tasks 6.5，对应 spec Req 9）。
 *
 * 5.7 的 ErrorContractTest 钉住了「七个码各从其端点出来、响应体只装 {code, message}、
 * 不回显调用方提交的凭证」。本类补它没覆盖的三样，且刻意跨 4xx 与 5xx 两个分支：
 *
 * - 哈希片段：库里存着真实 bcrypt 哈希时，登录失败的 4xx 响应原文里既不能出现整串哈希，
 *   也不能出现 $2a$/$2b$/$2y$ 这类前缀片段——只查字段名挡不住被拼进 message 的原文。
 * - 堆栈信息：任何错误响应都不得带 "at com."、".java:"、"Caused by:" 这类堆栈标记。
 * - 5xx 分支：未被 GlobalExceptionHandler 收口的意外异常，走 Spring 默认错误页。
 *   application.yml 没有开启 server.error.include-message/include-stacktrace，默认即
 *   never，所以异常消息与堆栈都不进响应体。这里用真实服务器（RANDOM_PORT + TestRestTemplate）
 *   把 CaptchaService.issue() 换成一个「消息里塞满邮箱、哈希与伪堆栈」的异常来验证：
 *   即便异常消息本身含敏感数据，默认错误页也不回显。这条同时端到端验证了 6.1 守卫对
 *   框架错误页（org.springframework 包 / 非 /api/auth 路径）的豁免——否则 500 会被守卫二次拦成 500。
 *
 * 决策：不为 5xx 新造第八个错误码。Req 9 的固定集合恰为七个（ErrorContractTest 断言 hasSize(7)），
 * 加 internal-error 码既破坏该断言又违反「码取自固定集合」；既有 GlobalExceptionHandler 的设计注释
 * 已明确「其它异常不兜底改写，让默认 500 暴露出来更容易在联调时被发现」。故 6.5 只验非泄漏属性，
 * 不改变 5xx 的响应结构。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureMockMvc
@Transactional
class ErrorResponseLeakTest {

    private static final String EMAIL = "leaky@example.com";
    private static final String PASSWORD = "correct-horse-battery";
    private static final String WRONG_PASSWORD = "correct-horse-battery-x";
    private static final String ORIGIN = "http://localhost:3000";

    /** 一段形状真实的 bcrypt 哈希：既做整串检索，也做 $2a$ 前缀片段检索。 */
    private static final String LEAK_HASH = "$2a$10$N9qo8uLOickgx2ZMRZoMyeIjZAgcfl7p92ldGxad68LJZdL17lhWy";

    /** 堆栈特征：出现在响应体里就说明内部实现细节被回显了。 */
    private static final List<String> STACK_MARKERS =
            List.of("at com.", ".java:", "java.lang.", "\tat ", "Exception:", "Caused by:");

    /** bcrypt 前缀片段：整串哈希被截断时，前缀仍会露出算法与 cost。 */
    private static final Set<String> BCRYPT_PREFIXES = Set.of("$2a$", "$2b$", "$2y$");

    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private AccountRepository accounts;
    @Autowired private PasswordHasher hasher;
    @Autowired private LoginThrottle throttle;
    @Autowired private TestRestTemplate restTemplate;

    @LocalServerPort private int port;

    /** 每个用例一个来源，避免与其它类共用真实单桶触发限流。 */
    @MockitoBean private SourceKeyResolver sourceKey;

    /** 只在 5xx 用例里把 issue() 换成抛异常；4xx 用例不触碰图形码。 */
    @MockitoBean private CaptchaService captcha;

    private final List<Long> accountIdsToClean = new ArrayList<>();

    @BeforeEach
    void isolateSource() {
        when(sourceKey.resolve(any())).thenReturn("leak-test-" + UUID.randomUUID());
    }

    @AfterEach
    void cleanAccountCounters() {
        accountIdsToClean.forEach(throttle::clearAccountFailures);
        accountIdsToClean.clear();
    }

    @Test
    @DisplayName("4xx：库里存着真实哈希时，登录失败响应不含哈希整串、前缀片段与堆栈")
    void fourxxCarriesNoHashFragmentOrStack() throws Exception {
        // Account 不提供哈希读方法（D5），所以拿创建时那一串真实哈希来做检索目标。
        String storedHash = hasher.hash(PASSWORD);
        saveAccountWithHash(EMAIL, storedHash);

        MvcResult result = login(EMAIL, WRONG_PASSWORD).andReturn();
        assertThat(result.getResponse().getStatus()).isEqualTo(HttpStatus.UNAUTHORIZED.value());

        String raw = result.getResponse().getContentAsString(StandardCharsets.UTF_8);
        assertCarriesNoSecrets(raw, storedHash, PASSWORD, WRONG_PASSWORD, EMAIL);
    }

    @Test
    @DisplayName("5xx：意外异常走默认错误页，异常消息里的邮箱/哈希/伪堆栈都不回显")
    void fiveHundredFromUnexpectedErrorLeaksNothing() {
        String poisonedMessage = "boom email=" + EMAIL + " hash=" + LEAK_HASH
                + "\n\tat com.luyi.myfirst.Fake.method(Fake.java:42)";
        when(captcha.issue()).thenThrow(new RuntimeException(poisonedMessage));

        ResponseEntity<String> response =
                restTemplate.getForEntity("http://localhost:" + port + "/api/auth/captcha", String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
        String raw = response.getBody() == null ? "" : response.getBody();
        assertCarriesNoSecrets(raw, EMAIL, LEAK_HASH, "boom", poisonedMessage);
    }

    @Test
    @DisplayName("反证：把哈希与堆栈刻意拼进响应体，检测器必须抓住——否则前两条只是永远为真的空断言")
    void leakDetectorIsSensitiveToAnActualLeak() {
        String leaking = "{\"code\":\"UNAUTHENTICATED\",\"message\":\"" + LEAK_HASH + " at com.x.Y.z(Y.java:1)\"}";
        assertThatThrownBy(() -> assertCarriesNoSecrets(leaking))
                .isInstanceOf(AssertionError.class);
    }

    /** 断言响应原文非空、且不含任何 bcrypt 前缀片段、堆栈标记与额外指定的敏感值。 */
    private void assertCarriesNoSecrets(String raw, String... extraForbidden) {
        assertThat(raw).as("错误响应体不应为空").isNotBlank();
        for (String prefix : BCRYPT_PREFIXES) {
            assertThat(raw).as("不得泄漏哈希前缀片段 %s", prefix).doesNotContain(prefix);
        }
        for (String marker : STACK_MARKERS) {
            assertThat(raw).as("不得泄漏堆栈标记 %s", marker).doesNotContain(marker);
        }
        for (String forbidden : extraForbidden) {
            assertThat(raw).as("不得回显敏感值").doesNotContain(forbidden);
        }
    }

    private org.springframework.test.web.servlet.ResultActions login(String email, String password) throws Exception {
        String body = objectMapper.writeValueAsString(new LoginRequest(email, password, null, null));
        return mockMvc.perform(MockMvcRequestBuilders.post("/api/auth/login")
                .header(HttpHeaders.ORIGIN, ORIGIN)
                .contentType(MediaType.APPLICATION_JSON)
                .content(body));
    }

    private void saveAccountWithHash(String email, String passwordHash) {
        Account account = Account.createActive(Ulid.generate(), email, passwordHash, "Leaky");
        Account saved = accounts.saveAndFlush(account);
        accountIdsToClean.add(saved.id());
    }
}
