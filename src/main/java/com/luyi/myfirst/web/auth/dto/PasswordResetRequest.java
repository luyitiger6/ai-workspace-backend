package com.luyi.myfirst.web.auth.dto;

/**
 * POST /api/auth/password-reset 的请求体。
 *
 * 三样东西缺一不可：邮箱（决定查哪个码）、重置码（唯一凭据）、新密码。
 * 邮箱与码一起提交而不是只交码 —— 码的缓存键是邮箱摘要，只凭码无法定位是哪个邮箱在改密码。
 */
public record PasswordResetRequest(String email, String code, String newPassword) {
}
