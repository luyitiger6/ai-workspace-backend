package com.luyi.myfirst.web.auth;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.luyi.myfirst.common.enums.AccountStatus;
import com.luyi.myfirst.common.id.Ulid;
import com.luyi.myfirst.common.security.PasswordHasher;
import com.luyi.myfirst.dao.auth.entity.Account;
import com.luyi.myfirst.dao.auth.repository.AccountRepository;
import com.luyi.myfirst.service.auth.support.LoginThrottle;
import com.luyi.myfirst.web.auth.dto.LoginRequest;
import com.luyi.myfirst.web.auth.interceptor.SourceKeyResolver;
import jakarta.servlet.http.Cookie;
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
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;
import org.springframework.transaction.annotation.Transactional;

import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 当前用户 / 登出 / 注销三个端点（tasks 5.6）。
 *
 * 三者的令牌都只从 cookie 读，所以本类没有一条用例是用 Authorization 头拿的——
 * 令牌一旦有第二条入口，「从 cookie 读」这条契约就只覆盖了一半。
 *
 * 验收句是「非 active 账户持有效令牌访问返回 401，不返回 403」：令牌本身完全合法
 * （签名、过期、jti 未吊销、ver 一致），只有状态不是 active。403 会告诉调用方
 * 「你已通过认证、只是没权限」，而这里的正确语义是「这个令牌不再代表任何人」。
 *
 * 登出与注销的 cookie 清除顺序（先让服务端失效、再清 cookie）在控制器里，本类
 * 从外部只能观察到结果：响应清了 cookie，且同一个旧 cookie 再访问得到 401。
 */
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class AccountEndpointsTest {

    private static final String EMAIL = "erin@example.com";
    private static final String PASSWORD = "correct-horse-battery";
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

    @MockitoBean
    private SourceKeyResolver sourceKey;

    private Long accountIdToClean;

    @BeforeEach
    void isolateSource() {
        when(sourceKey.resolve(any())).thenReturn("account-endpoints-" + UUID.randomUUID());
    }

    @AfterEach
    void cleanAccountCounter() {
        if (accountIdToClean != null) {
            throttle.clearAccountFailures(accountIdToClean);
        }
    }

    @Test
    @DisplayName("没有 cookie 访问 /me 得到 401：受保护路径不靠 Authorization 头兜底")
    void meWithoutCookieIsUnauthenticated() throws Exception {
        mockMvc.perform(MockMvcRequestBuilders.get("/api/auth/me"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHENTICATED"));
    }

    @Test
    @DisplayName("持有有效令牌的 active 账户：200，字段恰为白名单五项，且不含令牌与密码哈希")
    void meReturnsExactlyTheWhitelistedFields() throws Exception {
        String hash = hasher.hash(PASSWORD);
        Account account = saveAccount(hash);
        Cookie cookie = loginAndGetCookie();
        String token = cookie.getValue();

        MvcResult result = mockMvc.perform(MockMvcRequestBuilders.get("/api/auth/me").cookie(cookie))
                .andExpect(status().isOk())
                .andReturn();

        String body = result.getResponse().getContentAsString(StandardCharsets.UTF_8);
        JsonNode root = objectMapper.readTree(body);

        List<String> fields = new ArrayList<>();
        root.fieldNames().forEachRemaining(fields::add);
        // 「恰为五项」而不是「至少包含」：多一个字段就是多一份泄漏面，6.2 的白名单
        // 校验会把这条断言换成一个通用收集器，但今天它已经能拦住「顺手把实体序列化出去」。
        assertThat(fields).containsExactlyInAnyOrder(
                "publicId", "email", "displayName", "status", "createdAt");

        assertThat(root.get("publicId").asText()).isEqualTo(account.publicId());
        assertThat(root.get("email").asText()).isEqualTo(EMAIL);
        assertThat(root.get("displayName").asText()).isEqualTo("Erin");
        // 用小写库值而不是枚举名：对外契约不随 Java 类型改名而漂移。
        assertThat(root.get("status").asText()).isEqualTo("active");

        // createdAt 来自库侧默认值（`@Generated(INSERT)` 负责回读），不能是 null：
        // 这一条同时钉住了「实体加了 insertable=false 的列，读回来仍是 null」那个坑。
        assertThat(root.get("createdAt").asText()).isNotBlank();
        assertThat(Instant.parse(root.get("createdAt").asText())).isNotNull();

        assertThat(body)
                .doesNotContain(token)
                .doesNotContain(hash);
    }

    @Test
    @DisplayName("非 active 账户持有效令牌访问返回 401 而不是 403")
    void nonActiveAccountWithValidTokenIsUnauthenticated() throws Exception {
        Account account = saveAccount(hasher.hash(PASSWORD));
        Cookie cookie = loginAndGetCookie();

        // 令牌完全有效（签名、过期、jti 未吊销、ver 一致），唯一变化是状态。
        // 反射夹具与 LoginTest 同源：email_unverified 在生产不可达，也不该有生产入口。
        forceStatus(account, AccountStatus.EMAIL_UNVERIFIED);

        mockMvc.perform(MockMvcRequestBuilders.get("/api/auth/me").cookie(cookie))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHENTICATED"));
    }

    @Test
    @DisplayName("登出：204、cookie 置空且 Max-Age=0，旧令牌再访问即 401")
    void logoutClearsCookieAndRevokesCurrentToken() throws Exception {
        saveAccount(hasher.hash(PASSWORD));
        Cookie cookie = loginAndGetCookie();

        MvcResult result = mockMvc.perform(MockMvcRequestBuilders.post("/api/auth/logout")
                        .header(HttpHeaders.ORIGIN, ORIGIN)
                        .cookie(cookie))
                .andExpect(status().isNoContent())
                .andReturn();

        String setCookie = result.getResponse().getHeader(HttpHeaders.SET_COOKIE);
        // 清 cookie 与下发时属性逐项一致，否则浏览器把它当成另一个 cookie，原件留在原地。
        assertThat(setCookie)
                .contains(AuthCookie.NAME + "=;")
                .contains("Max-Age=0")
                .contains("HttpOnly")
                .contains("SameSite=Lax")
                .contains("Path=/");

        mockMvc.perform(MockMvcRequestBuilders.get("/api/auth/me").cookie(cookie))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHENTICATED"));
    }

    @Test
    @DisplayName("注销：204、账户落 deleted、此前签发的全部令牌一起失效")
    void deleteAccountRevokesAllPreviouslyIssuedTokens() throws Exception {
        saveAccount(hasher.hash(PASSWORD));
        Cookie first = loginAndGetCookie();
        Cookie second = loginAndGetCookie();

        MvcResult result = mockMvc.perform(MockMvcRequestBuilders.delete("/api/auth/account")
                        .header(HttpHeaders.ORIGIN, ORIGIN)
                        .cookie(first))
                .andExpect(status().isNoContent())
                .andReturn();

        assertThat(result.getResponse().getHeader(HttpHeaders.SET_COOKIE))
                .contains(AuthCookie.NAME + "=;")
                .contains("Max-Age=0");

        Account deleted = accounts.findById(accountIdToClean).orElseThrow();
        assertThat(deleted.status()).isEqualTo(AccountStatus.DELETED);
        // deletedAt 与状态必须同时落库：active_email 生成列靠它放行同邮箱重注册。
        assertThat(deleted.deletedAt()).isNotNull();
        assertThat(deleted.tokenVersion()).isPositive();

        // 两张令牌都失效——注销不是「吊销这一张」，是让该账户此前签发的全部令牌失效。
        mockMvc.perform(MockMvcRequestBuilders.get("/api/auth/me").cookie(first))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(MockMvcRequestBuilders.get("/api/auth/me").cookie(second))
                .andExpect(status().isUnauthorized());
    }

    private Cookie loginAndGetCookie() throws Exception {
        MvcResult result = mockMvc.perform(MockMvcRequestBuilders.post("/api/auth/login")
                        .header(HttpHeaders.ORIGIN, ORIGIN)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                new LoginRequest(EMAIL, PASSWORD, null, null))))
                .andExpect(status().isOk())
                .andReturn();
        Cookie cookie = result.getResponse().getCookie(AuthCookie.NAME);
        assertThat(cookie).isNotNull();
        return cookie;
    }

    private Account saveAccount(String passwordHash) {
        Account account = Account.createActive(Ulid.generate(), EMAIL, passwordHash, "Erin");
        Account saved = accounts.saveAndFlush(account);
        accountIdToClean = saved.id();
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
