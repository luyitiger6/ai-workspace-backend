package com.luyi.myfirst.web.auth.dto;

/**
 * GET /api/auth/captcha 的响应体。
 *
 * 只有 id 与图。答案、答案摘要、缓存键都不出现在这里 ——
 * 「响应禁含验证码」的结构性落地方式是「响应里根本没有这个字段」。
 */
public record CaptchaResponse(String captchaId, String image) {
}
