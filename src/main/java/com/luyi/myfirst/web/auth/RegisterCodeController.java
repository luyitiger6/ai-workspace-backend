package com.luyi.myfirst.web.auth;

import com.luyi.myfirst.service.auth.RegisterCodeService;
import com.luyi.myfirst.web.auth.dto.RegisterCodeRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 注册码换发端点。
 *
 * `202` 而不是 `200`：它只表示「请求已被受理」，不表示「码已发出」——
 * 等 5.2 接上防枚举后，已注册邮箱走的是同一个 202 但不投递（design 端点表）。
 */
@RestController
@RequestMapping("/api/auth")
public class RegisterCodeController {

    private final RegisterCodeService registerCodes;

    public RegisterCodeController(RegisterCodeService registerCodes) {
        this.registerCodes = registerCodes;
    }

    @PostMapping("/register-code")
    ResponseEntity<Void> registerCode(@RequestBody RegisterCodeRequest request) {
        registerCodes.issue(request.email(), request.captchaId(), request.captchaAnswer());
        return ResponseEntity.accepted().build();
    }
}
