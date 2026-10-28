package com.luyi.myfirst.web.auth;

import com.luyi.myfirst.service.auth.LoginService;
import com.luyi.myfirst.service.auth.model.LoginResult;
import com.luyi.myfirst.web.auth.dto.LoginRequest;
import com.luyi.myfirst.web.auth.dto.LoginResponse;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;

/**
 * 登录端点。
 *
 * 三态的差异全部来自服务端的状态判定：`200` 与 `Set-Cookie`、`403 + EMAIL_UNVERIFIED`、
 * `401 + UNAUTHENTICATED`。控制器里不写任何按状态的分支——一旦在这里加 if，
 * 「先凭证后状态」的顺序就有了第二个可以被改坏的地方。
 *
 * 令牌与注册一样只走 Set-Cookie，响应体里检索不到它。
 */
@RestController
@RequestMapping("/api/auth")
public class LoginController {

    private final LoginService loginService;
    private final AuthCookie authCookie;

    public LoginController(LoginService loginService, AuthCookie authCookie) {
        this.loginService = loginService;
        this.authCookie = authCookie;
    }

    @PostMapping("/login")
    ResponseEntity<LoginResponse> login(@RequestBody LoginRequest request,
                                        HttpServletResponse response) {
        LoginResult result = loginService.login(request.email(), request.password(), Instant.now());

        authCookie.write(response, result.token().value());

        return ResponseEntity.ok(new LoginResponse(
                result.publicId(), result.email(), result.displayName(), result.token().expiresAt()));
    }
}
