package com.luyi.myfirst.config;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.context.ConfigurationPropertiesAutoConfiguration;
import org.springframework.boot.autoconfigure.validation.ValidationAutoConfiguration;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;

import java.util.Arrays;

import static org.assertj.core.api.Assertions.assertThat;

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

    @Configuration(proxyBeanMethods = false)
    @EnableConfigurationProperties(AuthProperties.class)
    static class BindingConfig {
    }
}
