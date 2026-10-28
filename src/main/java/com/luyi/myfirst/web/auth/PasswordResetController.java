package com.luyi.myfirst.web.auth;

import com.luyi.myfirst.service.auth.PasswordResetService;
import com.luyi.myfirst.web.auth.dto.PasswordResetRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 重置密码端点。
 *
 * `200` 而不是 `204`：与 design 端点表一致。响应体为空——重置不下发令牌（见
 * `PasswordResetService` 的说明），也就不需要 `Set-Cookie`。
 *
 * 控制器里没有任何分支：格式、验码、账户存在性、旧令牌作废全在服务层，
 * 这里多一个 `if` 就等于多一个能改坏「码与账户存在性不可区分」的地方。
 */
@RestController
@RequestMapping("/api/auth")
public class PasswordResetController {

    private final PasswordResetService passwordResets;

    public PasswordResetController(PasswordResetService passwordResets) {
        this.passwordResets = passwordResets;
    }

    @PostMapping("/password-reset")
    ResponseEntity<Void> passwordReset(@RequestBody PasswordResetRequest request) {
        passwordResets.reset(request.email(), request.code(), request.newPassword());
        return ResponseEntity.ok().build();
    }
}
