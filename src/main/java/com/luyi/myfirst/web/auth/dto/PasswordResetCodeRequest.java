package com.luyi.myfirst.web.auth.dto;

/**
 * POST /api/auth/password-reset-code 的请求体。
 *
 * 形状与 {@link RegisterCodeRequest} 逐字段相同，但不合并：两个端点的用途不同，
 * 将来收紧其中一个（比如重置要求先过短信验证）时不该牵连另一个。
 */
public record PasswordResetCodeRequest(String email, String captchaId, String captchaAnswer) {
}
