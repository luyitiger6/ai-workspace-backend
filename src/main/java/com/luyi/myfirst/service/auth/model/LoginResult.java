package com.luyi.myfirst.service.auth.model;

/**
 * 登录结果。
 *
 * 与注册一样只带三个可回显的账户字段，加上一张令牌。过期时间也从令牌上取，
 * 响应体的 {@code expiresAt}、Set-Cookie 的 Max-Age 与令牌自身的 exp 因此同源，
 * 不会出现「三者各算一遍」的偏差。
 */
public record LoginResult(String publicId, String email, String displayName, IssuedToken token) {
}
