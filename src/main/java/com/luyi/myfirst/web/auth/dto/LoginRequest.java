package com.luyi.myfirst.web.auth.dto;

/**
 * POST /api/auth/login 的请求体。
 *
 * 邮箱在服务端归一化之后才用于查库，客户端传大写或带空白都能登录。
 *
 * `captchaId` 与 `captchaAnswer` 平时可以省略：只有同一账户连续失败达到阈值后
 * 服务端才要求图形码，届时缺失或答错一律 400 与 `CAPTCHA_INVALID`。
 * 字段可选而不是另开一个「预检」端点——预检端点自己会变成探测工具。
 */
public record LoginRequest(
        String email,
        String password,
        String captchaId,
        String captchaAnswer
) {
}
