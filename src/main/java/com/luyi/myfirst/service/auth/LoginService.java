package com.luyi.myfirst.service.auth;

import com.luyi.myfirst.service.auth.model.LoginResult;

import java.time.Instant;

/**
 * 登录（D3：先凭证，后状态）。
 *
 * 判定顺序是契约的一部分，不随实现方便调整：
 * 凭证不通过一律 {@code 401}，与目标账户处于什么状态无关；
 * {@code 403} 只在「密码也是对的」之后才可能出现。
 */
public interface LoginService {

    /**
     * 用邮箱与明文密码登录。
     *
     * @param now 签发时刻由调用方给出，测试才能断言 exp 与 iat 的差值等于配置的有效期
     * @return 账户可回显的三个字段与一张令牌；令牌只能交给认证 cookie 的唯一写入点
     * @throws com.luyi.myfirst.common.exception.BizException 入参不合法（{@code VALIDATION_FAILED}）、
     *         凭证不通过（{@code UNAUTHENTICATED}）、邮箱未验证（{@code EMAIL_UNVERIFIED}）
     */
    LoginResult login(String email, String rawPassword, Instant now);
}
