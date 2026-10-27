package com.luyi.myfirst.service.auth;

import com.luyi.myfirst.dao.auth.entity.Account;
import com.luyi.myfirst.dao.auth.entity.AuthSession;
import com.luyi.myfirst.dao.auth.repository.AccountRepository;
import com.luyi.myfirst.dao.auth.repository.AuthSessionRepository;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

import static org.hamcrest.Matchers.allOf;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 登出的两条边界：只吊销当前 jti、cookie 真被清掉。
 *
 * 端点同样是探针夹具——真正的 POST /api/auth/logout 随 5.6 落地；
 * 这里要守的是「登出服务 + 拦截器 + cookie 清除」这条组合，而不是路由。
 */
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
@Import(LogoutTest.ProbeConfig.class)
class LogoutTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private TokenIssuer issuer;

    @Autowired
    private AccountRepository accounts;

    @Autowired
    private AuthSessionRepository sessions;

    @Test
    @DisplayName("登出后当前令牌不可再访问，同账户其他令牌不受影响")
    void logoutRevokesOnlyCurrentToken() throws Exception {
        Account account = persistAccount();
        IssuedToken current = issueAndRegister(account, Instant.now());
        IssuedToken other = issueAndRegister(account, Instant.now());

        mockMvc.perform(post("/probe/logout").cookie(new Cookie(AuthCookie.NAME, current.value())))
                .andExpect(status().isNoContent());

        mockMvc.perform(get("/probe/protected").cookie(new Cookie(AuthCookie.NAME, current.value())))
                .andExpect(status().isUnauthorized());

        mockMvc.perform(get("/probe/protected").cookie(new Cookie(AuthCookie.NAME, other.value())))
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("登出响应清除认证 cookie：同名、空值、Max-Age=0")
    void logoutClearsAuthCookie() throws Exception {
        Account account = persistAccount();
        IssuedToken issued = issueAndRegister(account, Instant.now());

        mockMvc.perform(post("/probe/logout").cookie(new Cookie(AuthCookie.NAME, issued.value())))
                .andExpect(status().isNoContent())
                .andExpect(header().string(HttpHeaders.SET_COOKIE, allOf(
                        containsString(AuthCookie.NAME + "="),
                        containsString("Max-Age=0"),
                        containsString("Path=/"))));
    }

    @Test
    @DisplayName("第二次登出被拦截器拒绝：令牌已吊销，不再认它")
    void secondLogoutIsRejected() throws Exception {
        Account account = persistAccount();
        IssuedToken issued = issueAndRegister(account, Instant.now());
        Cookie cookie = new Cookie(AuthCookie.NAME, issued.value());

        mockMvc.perform(post("/probe/logout").cookie(cookie)).andExpect(status().isNoContent());

        // 重复登出不产生 500，而是与其它“持废令牌”请求同一种结局：401
        mockMvc.perform(post("/probe/logout").cookie(cookie)).andExpect(status().isUnauthorized());
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
            registry.addInterceptor(interceptor).addPathPatterns("/probe/**");
        }

        @Bean
        ProbeController probeController(LogoutService logoutService, AuthCookie authCookie) {
            return new ProbeController(logoutService, authCookie);
        }
    }

    @RestController
    static class ProbeController {

        private final LogoutService logoutService;
        private final AuthCookie authCookie;

        ProbeController(LogoutService logoutService, AuthCookie authCookie) {
            this.logoutService = logoutService;
            this.authCookie = authCookie;
        }

        @PostMapping("/probe/logout")
        ResponseEntity<Void> logout(HttpServletRequest request, HttpServletResponse response) {
            VerifiedToken verified = (VerifiedToken) request.getAttribute(AuthInterceptor.VERIFIED_ATTRIBUTE);
            logoutService.logout(verified);
            authCookie.clear(response);
            return ResponseEntity.noContent().build();
        }

        @GetMapping("/probe/protected")
        ResponseEntity<Map<String, String>> protectedEndpoint(HttpServletRequest request) {
            VerifiedToken verified = (VerifiedToken) request.getAttribute(AuthInterceptor.VERIFIED_ATTRIBUTE);
            return ResponseEntity.ok(Map.of("publicId", verified.account().publicId()));
        }
    }
}
