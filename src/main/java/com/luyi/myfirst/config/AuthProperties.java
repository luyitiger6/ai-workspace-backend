package com.luyi.myfirst.config;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import java.time.Duration;

/**
 * 认证链路的可调参数。
 *
 * 全部写在 application.yml 的 wanderchain.auth 下，本类刻意不给默认值：
 * 配置缺失时启动即失败，避免「改了一处配置却因代码里有兜底而没生效」。
 */
@Validated
@ConfigurationProperties(prefix = "wanderchain.auth")
public record AuthProperties(
        @NotNull Duration sessionTtl,
        @NotBlank String jwtSecret,
        @NotNull @Valid Captcha captcha,
        @NotNull @Valid EmailCode emailCode,
        @NotNull @Valid Login login
) {

    public AuthProperties {
        requireResolved("wanderchain.auth.jwt-secret", jwtSecret, "WANDERCHAIN_JWT_SECRET");
    }

    /**
     * 拦下「占位符没解析成功但被当成普通字符串放行」的情况。
     *
     * Spring Boot 的 Binder 解析不出的 ${...} 会原样保留，@NotBlank 见到的只是非空文本，
     * 校验形同虚设；这里显式把「配了但没生效」提前成启动失败。
     */
    private static void requireResolved(String property, String value, String envVar) {
        if (value != null && value.contains("${")) {
            throw new IllegalStateException(property + " 未能解析，当前值 " + value
                    + "；请设置环境变量 " + envVar);
        }
    }

    /** 图形码：一次性，答错即作废。 */
    public record Captcha(@NotNull Duration ttl) {
    }

    /** 邮箱码：注册码与重置码共用同一套参数，只是去向不同。 */
    public record EmailCode(
            @NotNull Duration ttl,
            @NotNull Duration resendCooldown,
            @Min(1) int maxVerifyAttempts,
            @Min(1) int dailyQuota
    ) {
    }

    /** 登录防爆破：账户维度失败后要求图形码，来源维度超阈直接拒绝。 */
    public record Login(
            @Min(1) int failThreshold,
            @NotNull Duration sourceWindow,
            @Min(1) int sourceMax,
            @NotNull Duration retryAfter
    ) {
    }
}
