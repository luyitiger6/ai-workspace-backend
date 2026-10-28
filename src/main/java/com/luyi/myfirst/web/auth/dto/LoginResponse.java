package com.luyi.myfirst.web.auth.dto;

import com.luyi.myfirst.common.web.AuthResponseBody;

import java.time.Instant;

/**
 * POST /api/auth/login 的响应体。
 *
 * 令牌不在这里——它只经 Set-Cookie 下发（spec「令牌不出现在响应体」）。
 * expiresAt 供客户端做倒计时，与 cookie 的 Max-Age、令牌自身的 exp 同源。
 */
public record LoginResponse(String publicId, String email, String displayName, Instant expiresAt) implements AuthResponseBody {
}
