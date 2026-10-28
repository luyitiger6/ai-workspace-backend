package com.luyi.myfirst.service.auth;

import com.luyi.myfirst.service.auth.model.Registration;

import java.time.Instant;

/**
 * 注册：验码通过后建号并直接签发令牌（首次注册即自动登录）。
 */
public interface RegisterService {

    /**
     * 注册一个新账户。
     *
     * @param now 签发时刻由调用方给出，测试才能断言 exp 与 iat 的差值等于配置的有效期
     */
    Registration register(String email, String code, String rawPassword, String displayName, Instant now);
}
