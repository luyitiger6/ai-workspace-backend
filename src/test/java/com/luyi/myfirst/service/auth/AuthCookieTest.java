package com.luyi.myfirst.service.auth;

import com.luyi.myfirst.config.AuthProperties;
import com.luyi.myfirst.web.auth.AuthCookie;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.mock.web.MockHttpServletResponse;

import java.time.Duration;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 认证 cookie 的属性契约（spec「认证 cookie 属性齐备」）。
 *
 * 这些属性没有一条会在写错时报错：漏了 HttpOnly 只是 XSS 时丢令牌，
 * 漏了 SameSite 只是跨站写请求悄悄带上 cookie，漏了 Max-Age 只是关了浏览器才失效。
 * 所以只能逐条断言。
 */
class AuthCookieTest {

    private static final String TOKEN = "header.payload.signature";

    @Test
    @DisplayName("属性齐备：HttpOnly、SameSite=Lax、Path=/、Max-Age 等于 session-ttl")
    void writesAllAttributes() {
        MockHttpServletResponse response = new MockHttpServletResponse();

        new AuthCookie(properties(false)).write(response, TOKEN);

        String header = response.getHeader(HttpHeaders.SET_COOKIE);
        assertThat(header).contains(AuthCookie.NAME + "=" + TOKEN);
        assertThat(header).contains("HttpOnly");
        assertThat(header).contains("SameSite=Lax");
        assertThat(header).contains("Path=/");
        assertThat(header).contains("Max-Age=" + Duration.ofDays(7).toSeconds());
        assertThat(header).doesNotContain("Secure");
    }

    @Test
    @DisplayName("Secure 跟随 cookie-secure 配置，不由 profile 推断")
    void addsSecureOnlyWhenConfigured() {
        MockHttpServletResponse response = new MockHttpServletResponse();

        new AuthCookie(properties(true)).write(response, TOKEN);

        assertThat(response.getHeader(HttpHeaders.SET_COOKIE)).contains("Secure");
    }

    @Test
    @DisplayName("令牌只出现在响应头：写入 cookie 不产生任何响应体内容")
    void leavesResponseBodyEmpty() throws Exception {
        MockHttpServletResponse response = new MockHttpServletResponse();

        new AuthCookie(properties(false)).write(response, TOKEN);

        assertThat(response.getContentAsString()).isEmpty();
        assertThat(response.getHeader(HttpHeaders.SET_COOKIE)).contains(TOKEN);
    }

    @Test
    @DisplayName("清除 cookie：同名、空值、Max-Age=0，其余属性与下发时一致")
    void clearsCookieWithSameAttributes() {
        MockHttpServletResponse response = new MockHttpServletResponse();

        new AuthCookie(properties(false)).clear(response);

        String header = response.getHeader(HttpHeaders.SET_COOKIE);
        assertThat(header).contains(AuthCookie.NAME + "=");
        assertThat(header).contains("Max-Age=0");
        assertThat(header).contains("Path=/");
        assertThat(header).contains("HttpOnly");
        assertThat(header).contains("SameSite=Lax");
    }

    @Test
    @DisplayName("从不下发 Domain：cookie 的作用域锁在当前主机，不被同父域下的其它站点共享")
    void neverWritesDomainAttribute() {
        MockHttpServletResponse issued = new MockHttpServletResponse();
        MockHttpServletResponse cleared = new MockHttpServletResponse();
        AuthCookie cookie = new AuthCookie(properties(false));

        cookie.write(issued, TOKEN);
        cookie.clear(cleared);

        // Domain 缺省即「仅当前主机」；一旦写上父域，同一父域下的任意站点都能收到令牌 cookie。
        assertThat(issued.getHeader(HttpHeaders.SET_COOKIE)).doesNotContain("Domain");
        assertThat(cleared.getHeader(HttpHeaders.SET_COOKIE)).doesNotContain("Domain");
    }

    private static AuthProperties properties(boolean cookieSecure) {
        return new AuthProperties(
                Duration.ofDays(7),
                "0123456789abcdef0123456789abcdef",
                cookieSecure,
                List.of("http://localhost:3000"),
                List.of(),
                new AuthProperties.Captcha(Duration.ofMinutes(2)),
                new AuthProperties.EmailCode(Duration.ofMinutes(5), Duration.ofSeconds(60), 5, 5),
                new AuthProperties.Login(5, Duration.ofMinutes(10), 20, Duration.ofSeconds(60)));
    }
}
