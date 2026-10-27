package com.luyi.myfirst.service.auth;

import com.luyi.myfirst.common.exception.AuthErrorEnum;
import com.luyi.myfirst.common.exception.BizException;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

/**
 * 受保护路径的认证拦截器。
 *
 * 只从 cookie 读令牌，不读 Authorization 头（D8）：令牌一旦允许两条入口，
 * 「响应体不含令牌」和「cookie 属性齐备」这两条契约就只覆盖了一半。
 *
 * 校验失败抛 {@link BizException} 而不是自己拼 JSON——错误体由 GlobalExceptionHandler
 * 统一产出，拦截器与控制器因此共享同一套 {code, message} 契约。
 */
@Component
public class AuthInterceptor implements HandlerInterceptor {

    /** 请求属性名：控制器从这里取「账户 + jti」，不重复解析令牌。 */
    public static final String VERIFIED_ATTRIBUTE = "wanderchain.auth.verified";

    /**
     * 注册顺序：必须晚于 {@link OriginGuard#ORDER}。
     *
     * 跨站请求先被来源校验拦下，就不会走到这里 —— 否则它会先得到一个 401，
     * 让「来源不可信」和「没登录」在调用方那里变成同一件事。
     */
    public static final int ORDER = 10;

    private final TokenVerifier verifier;

    public AuthInterceptor(TokenVerifier verifier) {
        this.verifier = verifier;
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        String token = readToken(request);
        VerifiedToken verified = token == null ? null : verifier.verify(token).orElse(null);
        if (verified == null) {
            throw new BizException(AuthErrorEnum.UNAUTHENTICATED);
        }
        request.setAttribute(VERIFIED_ATTRIBUTE, verified);
        return true;
    }

    private static String readToken(HttpServletRequest request) {
        Cookie[] cookies = request.getCookies();
        if (cookies == null) {
            return null;
        }
        for (Cookie cookie : cookies) {
            if (AuthCookie.NAME.equals(cookie.getName())) {
                return cookie.getValue();
            }
        }
        return null;
    }
}
