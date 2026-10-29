package com.luyi.myfirst.web.auth;

import com.luyi.myfirst.web.auth.interceptor.AuthInterceptor;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.transaction.annotation.Transactional;

import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.request;

/**
 * 拦截器口径从 {@code /api/auth/**} 扩到 {@code /api/**} 之后的等价性证明（design D4）。
 *
 * 这是整个变更里破坏面最大的一步：漏登记一个公开端点，已归档认证能力的场景会静默失效。
 * 所以这里逐行钉住 D4 的九宫格，并且不只断言「路由表怎么写」，还要断言
 * 「HTTP 上的实际归属」——路由表对了但装配没生效，靠前一半断言是发现不了的。
 *
 * 写请求一律带同源 Origin 头：否则请求会先被 {@code OriginGuard} 挡成 403
 * CROSS_SITE_REJECTED，「不是 401」就同时包含了「公开」与「被来源校验拦下」两种含义，
 * 断言会变得毫无判别力。
 */
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class AuthRouteMatrixTest {

    private static final String ORIGIN = "http://localhost:3000";
    private static final String INVALID_TOKEN = "not-a-real-token";

    @Autowired
    private MockMvc mockMvc;

    static Stream<Arguments> routeMatrix() {
        String postId = "postid0000000000000000001";
        String commentId = "commentid00000000000000001";
        return Stream.of(
                // 认证域
                Arguments.of("GET", "/api/auth/captcha", true),
                Arguments.of("POST", "/api/auth/register-code", true),
                Arguments.of("POST", "/api/auth/register", true),
                Arguments.of("POST", "/api/auth/password-reset-code", true),
                Arguments.of("POST", "/api/auth/password-reset", true),
                Arguments.of("POST", "/api/auth/login", true),
                Arguments.of("POST", "/api/auth/logout", false),
                Arguments.of("GET", "/api/auth/me", false),
                Arguments.of("DELETE", "/api/auth/account", false),
                // 帖子域：读公开，写受保护
                Arguments.of("GET", "/api/posts", true),
                Arguments.of("GET", "/api/posts/" + postId, true),
                Arguments.of("GET", "/api/posts/" + postId + "/comments", true),
                Arguments.of("POST", "/api/posts", false),
                Arguments.of("PUT", "/api/posts/" + postId, false),
                Arguments.of("DELETE", "/api/posts/" + postId, false),
                Arguments.of("POST", "/api/posts/" + postId + "/comments", false),
                Arguments.of("DELETE", "/api/posts/" + postId + "/comments/" + commentId, false),
                Arguments.of("POST", "/api/posts/" + postId + "/votes", false),
                Arguments.of("DELETE", "/api/posts/" + postId + "/votes", false),
                Arguments.of("POST", "/api/posts/" + postId + "/bookmarks", false),
                Arguments.of("DELETE", "/api/posts/" + postId + "/bookmarks", false));
    }

    @ParameterizedTest(name = "{0} {1} 公开={2}")
    @MethodSource("routeMatrix")
    @DisplayName("九宫格逐行：路由表与 HTTP 实际归属一致")
    void endpointsKeepTheirOwnership(String method, String path, boolean expectedPublic) throws Exception {
        assertThat(AuthInterceptor.isPublicRoute(method, path))
                .as("路由表对 %s %s 的登记", method, path)
                .isEqualTo(expectedPublic);

        MvcResult result = mockMvc.perform(builder(method, path)).andReturn();
        int status = result.getResponse().getStatus();

        if (expectedPublic) {
            assertThat(status)
                    .as("公开端点 %s %s 不应因缺少令牌被拒（实际 %s）", method, path, status)
                    .isNotEqualTo(401);
        } else {
            assertThat(status).as("受保护端点 %s %s", method, path).isEqualTo(401);
            assertThat(result.getResponse().getContentAsString())
                    .contains("UNAUTHENTICATED");
        }
    }

    @Test
    @DisplayName("公开读带无效令牌仍放行（尽力解析，失败静默）")
    void publicReadIgnoresInvalidToken() throws Exception {
        MvcResult result = mockMvc.perform(builder("GET", "/api/auth/captcha")
                        .cookie(new Cookie(AuthCookie.NAME, INVALID_TOKEN)))
                .andReturn();
        assertThat(result.getResponse().getStatus()).isEqualTo(200);
    }

    @Test
    @DisplayName("受保护路径带无效令牌仍 401")
    void protectedReadRejectsInvalidToken() throws Exception {
        MvcResult result = mockMvc.perform(builder("GET", "/api/auth/me")
                        .cookie(new Cookie(AuthCookie.NAME, INVALID_TOKEN)))
                .andReturn();
        assertThat(result.getResponse().getStatus()).isEqualTo(401);
        assertThat(result.getResponse().getContentAsString()).contains("UNAUTHENTICATED");
    }

    @Test
    @DisplayName("未登记的路径默认拒绝：/api/anything 无令牌 401")
    void unregisteredApiPathIsDeniedByDefault() throws Exception {
        assertThat(AuthInterceptor.isPublicRoute("GET", "/api/anything")).isFalse();
        MvcResult result = mockMvc.perform(builder("GET", "/api/anything")).andReturn();
        assertThat(result.getResponse().getStatus()).isEqualTo(401);
    }

    private static MockHttpServletRequestBuilder builder(String method, String path) {
        return request(HttpMethod.valueOf(method), path).header(HttpHeaders.ORIGIN, ORIGIN);
    }
}
