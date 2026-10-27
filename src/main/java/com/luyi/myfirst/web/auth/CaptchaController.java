package com.luyi.myfirst.web.auth;

import com.luyi.myfirst.service.auth.CaptchaIssue;
import com.luyi.myfirst.service.auth.CaptchaService;
import com.luyi.myfirst.web.auth.dto.CaptchaResponse;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 图形码端点（design 端点表的第一条）。
 *
 * 无状态、无副作用、可重复调用：看不清就刷新是前端局部行为，服务端只需保证每次调用都给一对新的
 * id 与图（D9）。它也是这里唯一不需要认证的入口——要认证才能取图形码的话，登录失败后的强制校验就没法用。
 */
@RestController
@RequestMapping("/api/auth")
public class CaptchaController {

    private final CaptchaService captcha;

    public CaptchaController(CaptchaService captcha) {
        this.captcha = captcha;
    }

    @GetMapping("/captcha")
    CaptchaResponse captcha() {
        CaptchaIssue issue = captcha.issue();
        return new CaptchaResponse(issue.captchaId(), issue.image());
    }
}
