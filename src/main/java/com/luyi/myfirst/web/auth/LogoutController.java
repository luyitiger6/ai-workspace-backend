package com.luyi.myfirst.web.auth;

import com.luyi.myfirst.service.auth.LogoutService;
import com.luyi.myfirst.service.auth.model.VerifiedToken;
import com.luyi.myfirst.web.auth.interceptor.AuthInterceptor;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 登出端点。
 *
 * 两件事的顺序是刻意的：先吊销会话行，再清 cookie。反过来（先清 cookie 再吊销）
 * 一旦吊销失败，浏览器手里已经没有令牌，用户看到的是「登出成功了」，
 * 而服务端那张令牌仍然有效——这正是「登出后令牌失效」最容易被架空的方式。
 *
 * 没有 cookie 或令牌已失效时也不会走到这里：拦截器先给 401。
 */
@RestController
@RequestMapping("/api/auth")
public class LogoutController {

    private final LogoutService logoutService;
    private final AuthCookie authCookie;

    public LogoutController(LogoutService logoutService, AuthCookie authCookie) {
        this.logoutService = logoutService;
        this.authCookie = authCookie;
    }

    @PostMapping("/logout")
    ResponseEntity<Void> logout(@RequestAttribute(AuthInterceptor.VERIFIED_ATTRIBUTE) VerifiedToken verified,
                                HttpServletResponse response) {
        logoutService.logout(verified);
        authCookie.clear(response);
        return ResponseEntity.noContent().build();
    }
}
