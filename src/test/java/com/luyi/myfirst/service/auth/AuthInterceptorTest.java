package com.luyi.myfirst.service.auth;

import com.luyi.myfirst.service.auth.model.IssuedToken;
import com.luyi.myfirst.service.auth.model.VerifiedToken;
import com.luyi.myfirst.service.auth.support.TokenIssuer;

import com.luyi.myfirst.dao.auth.entity.Account;
import com.luyi.myfirst.dao.auth.entity.AuthSession;
import com.luyi.myfirst.dao.auth.repository.AccountRepository;
import com.luyi.myfirst.dao.auth.repository.AuthSessionRepository;
import com.luyi.myfirst.web.auth.AuthCookie;
import com.luyi.myfirst.web.auth.interceptor.AuthInterceptor;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.http.ResponseEntity;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 受保护路径的 401 契约：被篡改、过期、已吊销、未携带 cookie 四类请求都不能进门。
 *
 * 探针控制器是测试夹具而不是生产端点：3.2 要守的是拦截器，
 * 真正的 /api/auth/me 随 5.6 落地，到时候只需换路径，断言原样复用。
 */
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
@Import(AuthInterceptorTest.ProbeConfig.class)
class AuthInterceptorTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private TokenIssuer issuer;

    @Autowired
    private AccountRepository accounts;

    @Autowired
    private AuthSessionRepository sessions;

    @Test
    @DisplayName("未携带 cookie：401 与 UNAUTHENTICATED")
    void rejectsMissingCookie() throws Exception {
        mockMvc.perform(get("/probe/protected"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHENTICATED"));
    }

    @Test
    @DisplayName("被篡改的令牌：401")
    void rejectsTamperedToken() throws Exception {
        Account account = persistAccount();
        IssuedToken issued = issueAndRegister(account, Instant.now());

        mockMvc.perform(get("/probe/protected").cookie(new Cookie(AuthCookie.NAME, issued.value() + "x")))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHENTICATED"));
    }

    @Test
    @DisplayName("过期令牌：401")
    void rejectsExpiredToken() throws Exception {
        Account account = persistAccount();
        String token = issuer.issue(account, Instant.now().minus(Duration.ofDays(8))).value();

        mockMvc.perform(get("/probe/protected").cookie(new Cookie(AuthCookie.NAME, token)))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHENTICATED"));
    }

    @Test
    @DisplayName("已吊销的令牌：401")
    void rejectsRevokedToken() throws Exception {
        Account account = persistAccount();
        IssuedToken issued = issuer.issue(account, Instant.now());
        AuthSession session = sessions.save(AuthSession.open(account.id(), issued.jti(), issued.expiresAt()));
        session.revoke(Instant.now());
        sessions.save(session);

        mockMvc.perform(get("/probe/protected").cookie(new Cookie(AuthCookie.NAME, issued.value())))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHENTICATED"));
    }

    @Test
    @DisplayName("有效令牌：200，控制器拿到的是已校验账户")
    void acceptsValidToken() throws Exception {
        Account account = persistAccount();
        IssuedToken issued = issueAndRegister(account, Instant.now());

        mockMvc.perform(get("/probe/protected").cookie(new Cookie(AuthCookie.NAME, issued.value())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.publicId").value(account.publicId()));
    }

    private Account persistAccount() {
        Account account = Account.createActive(
                UUID.randomUUID().toString().replace("-", "").substring(0, 26),
                "u-" + UUID.randomUUID() + "@example.com",
                "$2a$10$abcdefghijklmnopqrstuv",
                "Alice");
        return accounts.save(account);
    }

    private IssuedToken issueAndRegister(Account account, Instant issuedAt) {
        IssuedToken issued = issuer.issue(account, issuedAt);
        sessions.save(AuthSession.open(account.id(), issued.jti(), issued.expiresAt()));
        return issued;
    }

    @TestConfiguration
    static class ProbeConfig implements WebMvcConfigurer {

        private final AuthInterceptor interceptor;

        ProbeConfig(AuthInterceptor interceptor) {
            this.interceptor = interceptor;
        }

        @Override
        public void addInterceptors(InterceptorRegistry registry) {
            registry.addInterceptor(interceptor).addPathPatterns("/probe/protected");
        }

        @Bean
        ProbeController probeController() {
            return new ProbeController();
        }
    }

    @RestController
    static class ProbeController {

        @GetMapping("/probe/protected")
        ResponseEntity<Map<String, String>> protectedEndpoint(HttpServletRequest request) {
            VerifiedToken verified = (VerifiedToken) request.getAttribute(AuthInterceptor.VERIFIED_ATTRIBUTE);
            return ResponseEntity.ok(Map.of("publicId", verified.account().publicId()));
        }
    }
}
