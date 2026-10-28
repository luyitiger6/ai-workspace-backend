package com.luyi.myfirst.web.auth.dto;

/**
 * POST /api/auth/register 的请求体。
 *
 * displayName 可空：不传时取邮箱 @ 前那一段。它是展示用字段，不该成为注册的第四道门槛。
 */
public record RegisterRequest(String email, String code, String password, String displayName) {
}
