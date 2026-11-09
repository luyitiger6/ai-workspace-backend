package com.luyi.myfirst.config;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.context.ConfigurationPropertiesAutoConfiguration;
import org.springframework.boot.autoconfigure.validation.ValidationAutoConfiguration;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;

import java.time.Duration;
import java.util.Arrays;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 配置绑定的边界：缺项、占位符没解析，都必须让容器起不来。
 *
 * 这组断言守的不是「能读到值」，而是「读不到时不会拿一个假值继续跑」。
 * 起因是一次真实事故：Spring Boot 的 Binder 对解析不出的 ${...} 原样保留，
 * @NotBlank 见到非空字符串就放行，缺了 JWT 密钥应用照样启动。
 */
class AuthPropertiesBindingTest {

    private static final String SECRET = "wanderchain.auth.jwt-secret=0123456789abcdef0123456789abcdef";

    private static final String[] COMPLETE = {
            "wanderchain.auth.session-ttl=7d",
            SECRET,
            "wanderchain.auth.cookie-secure=false",
            "wanderchain.auth.allowed-origins[0]=http://localhost:3000",
            "wanderchain.auth.trusted-proxies[0]=10.0.0.0/8",
            "wanderchain.auth.captcha.ttl=2m",
            "wanderchain.auth.email-code.ttl=5m",
            "wanderchain.auth.email-code.resend-cooldown=60s",
            "wanderchain.auth.email-code.max-verify-attempts=5",
            "wanderchain.auth.email-code.daily-quota=5",
            "wanderchain.auth.login.fail-threshold=5",
            "wanderchain.auth.login.source-window=10m",
            "wanderchain.auth.login.source-max=20",
            "wanderchain.auth.login.retry-after=60s"
    };

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withUserConfiguration(BindingConfig.class)
            .withConfiguration(AutoConfigurations.of(
                    ConfigurationPropertiesAutoConfiguration.class,
                    ValidationAutoConfiguration.class));

    @Test
    @DisplayName("完整配置：绑定成功，值可直接使用")
    void bindsWhenComplete() {
        runner.withPropertyValues(COMPLETE).run(context -> {
            assertThat(context).hasNotFailed();
            AuthProperties props = context.getBean(AuthProperties.class);
            assertThat(props.emailCode().dailyQuota()).isEqualTo(5);
            assertThat(props.login().retryAfter()).hasSeconds(60);
            assertThat(props.allowedOrigins()).containsExactly("http://localhost:3000");
            assertThat(props.trustedProxies()).containsExactly("10.0.0.0/8");
        });
    }

    @Test
    @DisplayName("缺 session-ttl：容器启动失败，而不是拿 null 继续跑")
    void failsWhenDurationMissing() {
        runner.withPropertyValues(without("wanderchain.auth.session-ttl=7d"))
                .run(context -> assertThat(context).hasFailed());
    }

    @Test
    @DisplayName("缺 jwt-secret：容器启动失败")
    void failsWhenSecretMissing() {
        runner.withPropertyValues(without(SECRET))
                .run(context -> assertThat(context).hasFailed());
    }

    @Test
    @DisplayName("缺 cookie-secure：容器启动失败，而不是默默按 false 处理")
    void failsWhenCookieSecureMissing() {
        runner.withPropertyValues(without("wanderchain.auth.cookie-secure=false"))
                .run(context -> assertThat(context).hasFailed());
    }

    @Test
    @DisplayName("缺 allowed-origins：容器启动失败，而不是拿空清单把每个写请求都拒掉")
    void failsWhenAllowedOriginsMissing() {
        runner.withPropertyValues(without("wanderchain.auth.allowed-origins[0]=http://localhost:3000"))
                .run(context -> assertThat(context).hasFailed());
    }

    @Test
    @DisplayName("空 trusted-proxies：构造即失败，错误信息点名配置项")
    void rejectsEmptyTrustedProxies() {
        // 空信任边界不是「没有代理」的表达，而是「所有请求共用一个未知来源桶」：
        // 来源限流会退化成全站一份额度，单个攻击者打满即可锁死全站。
        assertThatThrownBy(() -> properties(List.of()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("trusted-proxies");
    }

    @Test
    @DisplayName("回环信任边界：本地开发的取值可正常构造")
    void acceptsLoopbackTrustedProxies() {
        assertThat(properties(List.of("127.0.0.1/32", "::1/128")).trustedProxies())
                .containsExactly("127.0.0.1/32", "::1/128");
    }

    @Test
    @DisplayName("缺 trusted-proxies：容器启动失败——缺项与空列表都不是合法状态")
    void failsWhenTrustedProxiesMissing() {
        runner.withPropertyValues(without("wanderchain.auth.trusted-proxies[0]=10.0.0.0/8"))
                .run(context -> assertThat(context).hasFailed());
    }

    @Test
    @DisplayName("jwt-secret 的占位符没解析成真值：容器启动失败")
    void failsWhenSecretPlaceholderUnresolved() {
        // 用一个必定不存在的变量名，避免本机真实环境变量把这条断言变成假阳性
        runner.withPropertyValues(without(SECRET))
                .withPropertyValues("wanderchain.auth.jwt-secret=${WANDERCHAIN_TEST_UNSET_VAR}")
                .run(context -> assertThat(context)
                        .hasFailed()
                        .getFailure()
                        // Spring 会把顶层异常包成固定的 ConfigurationPropertiesBindException 文案，
                        // 真正的原因在 cause 链里，所以查堆栈而不是查一级消息
                        .hasStackTraceContaining("WANDERCHAIN_TEST_UNSET_VAR"));
    }

    private static String[] without(String excluded) {
        return Arrays.stream(COMPLETE).filter(value -> !value.equals(excluded)).toArray(String[]::new);
    }

    /** 直接构造一条完整配置，只替换信任边界：用于绕过 Binder 单独验证构造期的自检。 */
    private static AuthProperties properties(List<String> trustedProxies) {
        return new AuthProperties(
                Duration.ofDays(7),
                "0123456789abcdef0123456789abcdef",
                false,
                List.of("http://localhost:3000"),
                trustedProxies,
                new AuthProperties.Captcha(Duration.ofMinutes(2)),
                new AuthProperties.EmailCode(Duration.ofMinutes(5), Duration.ofSeconds(60), 5, 5),
                new AuthProperties.Login(5, Duration.ofMinutes(10), 20, Duration.ofSeconds(60)));
    }

    @Configuration(proxyBeanMethods = false)
    @EnableConfigurationProperties(AuthProperties.class)
    static class BindingConfig {
    }
}
