package com.luyi.myfirst.web.auth;

import com.luyi.myfirst.service.auth.PasswordResetCodeService;
import com.luyi.myfirst.web.auth.dto.PasswordResetCodeRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 重置码换发端点。
 *
 * `202` 与注册码端点同义：只表示「请求已被受理」，不表示「码已发出」。
 * 未注册与已注销的邮箱走的是同一条 202 但不投递的路径（spec「密码找回」），
 * 响应体逐字段一致，所以这个端点的返回值里没有任何可供区分的字段。
 */
@RestController
@RequestMapping("/api/auth")
public class PasswordResetCodeController {

    private final PasswordResetCodeService resetCodes;

    public PasswordResetCodeController(PasswordResetCodeService resetCodes) {
        this.resetCodes = resetCodes;
    }

    @PostMapping("/password-reset-code")
    ResponseEntity<Void> passwordResetCode(@RequestBody PasswordResetCodeRequest request) {
        resetCodes.issue(request.email(), request.captchaId(), request.captchaAnswer());
        return ResponseEntity.accepted().build();
    }
}
