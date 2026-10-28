package com.luyi.myfirst.web.auth.dto;

/**
 * POST /api/auth/login 的请求体。
 *
 * 只要凭证两个字段：邮箱在服务端归一化之后才用于查库，客户端传大写或带空白都能登录。
 */
public record LoginRequest(String email, String password) {
}
