package com.luyi.myfirst.web.auth;

import com.luyi.myfirst.service.auth.AccountService;
import com.luyi.myfirst.service.auth.model.VerifiedToken;
import com.luyi.myfirst.web.auth.interceptor.AuthInterceptor;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;

/**
 * 账户注销端点（`DELETE /api/auth/account`）。
 *
 * 状态判定不在控制器里：非 active 的账户挡在拦截器，走到这里的账户一定是 active，
 * 因此 {@code Account#delete} 不会撞非法迁移。控制器只负责「注销 + 清 cookie」两件事，
 * 顺序与登出相同——先让服务端失效，再让客户端丢掉令牌。
 */
@RestController
@RequestMapping("/api/auth")
public class AccountController {

    private final AccountService accountService;
    private final AuthCookie authCookie;

    public AccountController(AccountService accountService, AuthCookie authCookie) {
        this.accountService = accountService;
        this.authCookie = authCookie;
    }

    @DeleteMapping("/account")
    ResponseEntity<Void> delete(@RequestAttribute(AuthInterceptor.VERIFIED_ATTRIBUTE) VerifiedToken verified,
                                HttpServletResponse response) {
        accountService.delete(verified, Instant.now());
        authCookie.clear(response);
        return ResponseEntity.noContent().build();
    }
}
