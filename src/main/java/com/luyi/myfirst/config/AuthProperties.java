package com.luyi.myfirst.config;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import java.time.Duration;
import java.util.List;

/**
 * 认证链路的可调参数。
 *
 * 全部写在 application.yml 的 wanderchain.auth 下，本类刻意不给默认值：
 * 配置缺失时启动即失败，避免「改了一处配置却因代码里有兜底而没生效」。
 *
 * cookieSecure 决定 Set-Cookie 是否带 Secure：本地 HTTP 开发置 false，生产必须 true。
 * 它不做 profile 推断——猜出来的安全属性等于没有。
 *
 * allowedOrigins 是写请求的来源白名单（spec「写请求的来源校验」里的「已登记站点来源」）。
 * 它是登记清单而不是推导结果：子域改了没登记就自动被拒，正是「纵深防御」要的效果。
 * 空列表同样让启动失败——空清单会把每个写请求都拒掉，那种故障现场比启动失败难查得多。
 *
 * trustedProxies 是来源限流的信任边界（spec「登录失败以来源限流代替账户锁定」）：
 * 只有来自这些地址或网段的请求，其转发头才被采信。空列表不是「没有代理」的表达，
 * 而是「所有请求共用一个未知来源桶」——它让来源限流退化为全站共享一份额度，
 * 单个攻击者打满即可锁死全站，因此空列表 MUST 让启动失败。
 * 本地开发填回环（127.0.0.1/32、::1/128），生产填前端代理所在网段。
 */
@Validated
@ConfigurationProperties(prefix = "wanderchain.auth")
public record AuthProperties(
        @NotNull Duration sessionTtl,
        @NotBlank String jwtSecret,
        @NotNull Boolean cookieSecure,
        @NotEmpty List<String> allowedOrigins,
        @NotNull List<String> trustedProxies,
        @NotNull @Valid Captcha captcha,
        @NotNull @Valid EmailCode emailCode,
        @NotNull @Valid Login login
) {

    public AuthProperties {
        requireResolved("wanderchain.auth.jwt-secret", jwtSecret, "WANDERCHAIN_JWT_SECRET");
        requireTrustedProxies(trustedProxies);
    }

    /**
     * 拒绝空的信任边界。
     *
     * 空列表在运行期没有任何可观察的异常：所有请求一起落进「未知来源」桶，
     * 现象是「全站被同一个人限流」，而配置看起来是「配了、也没报错」。
     * 把它提前成启动失败，故障现场才会出现在部署阶段而不是攻击发生时。
     * 这里同时拦 null：缺项时 @NotNull 也会报错，但那条消息不会说明为什么这一项不能空。
     */
    private static void requireTrustedProxies(List<String> trustedProxies) {
        if (trustedProxies == null || trustedProxies.isEmpty()) {
            throw new IllegalStateException(
                    "wanderchain.auth.trusted-proxies 缺失或为空：空信任边界会让全部请求归入"
                            + "「未知来源」单桶，来源限流退化为全站共享一份额度；"
                            + "本地开发填 127.0.0.1/32、::1/128，生产填前端代理所在网段");
        }
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
