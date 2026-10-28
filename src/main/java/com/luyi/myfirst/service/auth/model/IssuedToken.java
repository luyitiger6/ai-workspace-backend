package com.luyi.myfirst.service.auth.model;

import java.time.Instant;

/**
 * 签发结果。
 *
 * value 只允许交给 {@link AuthCookie} 写进 Set-Cookie，不得进入任何响应体；
 * jti 是登出时按它吊销的那把钥匙；expiresAt 用于调用方判断是否需要另行记录。
 */
public record IssuedToken(String value, String jti, Instant expiresAt) {
}
