package com.luyi.myfirst.service.auth;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.luyi.myfirst.common.enums.AccountStatus;
import com.luyi.myfirst.common.id.Ulid;
import com.luyi.myfirst.common.security.PasswordHasher;
import com.luyi.myfirst.config.AuthProperties;
import com.luyi.myfirst.dao.auth.entity.Account;
import com.luyi.myfirst.dao.auth.repository.AccountRepository;
import com.luyi.myfirst.dao.auth.repository.AuthSessionRepository;
import com.luyi.myfirst.service.auth.model.VerifiedToken;
import com.luyi.myfirst.service.auth.support.TokenVerifier;
import com.luyi.myfirst.web.auth.AuthCookie;
import com.luyi.myfirst.web.auth.dto.LoginRequest;
import com.luyi.myfirst.web.auth.dto.LoginResponse;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;
import org.springframework.transaction.annotation.Transactional;

import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 登录端点的契约（tasks 5.3）：先凭证，后状态；三态的响应码由状态唯一决定。
 *
 * 走 HTTP 而不是直接调 service：三态里有两条是「响应不可区分」（已注销 vs 密码错、
 * 邮箱不存在 vs 密码错），只有穿过 GlobalExceptionHandler 与真实序列化才算数。
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

    private ResultActions login(String email, String password) throws Exception {
        return mockMvc.perform(MockMvcRequestBuilders.post("/api/auth/login")
                .contentType(MediaType.APPLICATION_JSON)
                .header(HttpHeaders.ORIGIN, ORIGIN)
                .content(objectMapper.writeValueAsString(new LoginRequest(email, password))));
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
        return accounts.saveAndFlush(account);
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
