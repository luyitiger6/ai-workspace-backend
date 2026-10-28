package com.luyi.myfirst.web.auth;

import com.luyi.myfirst.service.auth.RegisterService;
import com.luyi.myfirst.service.auth.model.Registration;
import com.luyi.myfirst.web.auth.dto.RegisterRequest;
import com.luyi.myfirst.web.auth.dto.RegisterResponse;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;

/**
 * 注册端点。
 *
 * `200` 而不是 `202`：这里确实建了账户、也确实签发了令牌，与只表示「请求已受理」的
 * `register-code` 不是一回事（design 端点表）。
 *
 * 响应体只放可回显的账户字段，令牌单独走 Set-Cookie：前端拿不到令牌字符串，
 * 也就不存在把它存进 localStorage 的那条路。
 */
@RestController
@RequestMapping("/api/auth")
public class RegisterController {

    private final RegisterService registerService;
    private final AuthCookie authCookie;

    public RegisterController(RegisterService registerService, AuthCookie authCookie) {
        this.registerService = registerService;
        this.authCookie = authCookie;
    }

    @PostMapping("/register")
    ResponseEntity<RegisterResponse> register(@RequestBody RegisterRequest request,
                                              HttpServletResponse response) {
        Registration registration = registerService.register(
                request.email(), request.code(), request.password(), request.displayName(), Instant.now());

        authCookie.write(response, registration.token().value());

        return ResponseEntity.ok(new RegisterResponse(
                registration.publicId(), registration.email(), registration.displayName()));
    }
}
