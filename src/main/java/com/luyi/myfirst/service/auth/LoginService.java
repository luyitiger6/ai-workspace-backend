package com.luyi.myfirst.service.auth;

import com.luyi.myfirst.service.auth.model.LoginAttempt;
import com.luyi.myfirst.service.auth.model.LoginResult;

import java.time.Instant;

/**
 * 登录（D3：先凭证，后状态）。
 *
 * 判定顺序是契约的一部分，不随实现方便调整：
 * 来源限流先于一切（否则限流对爆破无效）；凭证不通过一律 {@code 401}，
 * 与目标账户处于什么状态无关；{@code 403} 只在「密码也是对的」之后才可能出现。
 */
public interface LoginService {

    /**
     * 完成一次登录尝试。
     *
     * @param now 签发时刻由调用方给出，测试才能断言 exp 与 iat 的差值等于配置的有效期
     * @return 账户可回显的三个字段与一张令牌；令牌只能交给认证 cookie 的唯一写入点
     * @throws com.luyi.myfirst.common.exception.BizException 入参不合法（{@code VALIDATION_FAILED}）、
     *         来源超出窗口额度（{@code RATE_LIMITED}，带 {@code Retry-After}）、
     *         账户要求图形码但未提交或答错（{@code CAPTCHA_INVALID}）、
     *         凭证不通过（{@code UNAUTHENTICATED}）、邮箱未验证（{@code EMAIL_UNVERIFIED}）
     */
    LoginResult login(LoginAttempt attempt, Instant now);
}
