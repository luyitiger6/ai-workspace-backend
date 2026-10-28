package com.luyi.myfirst.service.auth.model;

/**
 * 一次登录尝试的全部入参（D3）。
 *
 * {@code sourceKey} 放在这里而不是让服务层自己去读请求：来源怎么认定属 Web 边界
 * （见 {@code SourceKeyResolver}），而限流计数在服务层——两者只有这一处接缝。
 *
 * {@code captchaId} 与 {@code captchaAnswer} 平时可以为 null：只有账户连续失败
 * 达到阈值后，它们才参与判定。
 */
public record LoginAttempt(
        String email,
        String rawPassword,
        String captchaId,
        String captchaAnswer,
        String sourceKey
) {
}
