package com.luyi.myfirst.service.auth;

import com.luyi.myfirst.config.AuthProperties;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseCookie;
import org.springframework.stereotype.Component;

/**
 * 认证 cookie 的唯一写入点（D8）。
 *
 * 令牌只从 Set-Cookie 出去，响应体里没有它的容身之处。
 * 属性一次写齐：HttpOnly（脚本读不到）、SameSite=Lax（跨站的写请求根本不带 cookie）、
 * Path=/（全站可见）、Max-Age 与 session-ttl 同值。
 *
 * Secure 由配置决定而不按 profile 猜：本地 HTTP 开发置 false，生产必须 true，
 * 猜错一次（例如把 Secure 默认成 true）本地就没法登录，猜错另一头等于把令牌放进明文。
 */
@Component
public class AuthCookie {

    public static final String NAME = "wc_session";

    private final boolean secure;
    private final long maxAgeSeconds;

    public AuthCookie(AuthProperties properties) {
        this.secure = properties.cookieSecure();
        this.maxAgeSeconds = properties.sessionTtl().toSeconds();
    }

    /** 下发认证 cookie。响应体是否为空由调用方保证，本方法只碰响应头。 */
    public void write(HttpServletResponse response, String token) {
        response.addHeader(HttpHeaders.SET_COOKIE, build(token).toString());
    }

    /**
     * 清除认证 cookie：同名、空值、Max-Age=0。
     *
     * 属性必须与下发时逐项一致——Path 或 Secure 差一项，浏览器就把它当成另一个 cookie，
     * 结果是原件留在原地：登出看起来成功了，下一次请求仍然带着旧令牌。
     */
    public void clear(HttpServletResponse response) {
        response.addHeader(HttpHeaders.SET_COOKIE, expired().toString());
    }

    private ResponseCookie expired() {
        return ResponseCookie.from(NAME, "")
                .httpOnly(true)
                .secure(secure)
                .sameSite("Lax")
                .path("/")
                .maxAge(0)
                .build();
    }

    private ResponseCookie build(String token) {
        return ResponseCookie.from(NAME, token)
                .httpOnly(true)
                .secure(secure)
                .sameSite("Lax")
                .path("/")
                .maxAge(maxAgeSeconds)
                .build();
    }
}
