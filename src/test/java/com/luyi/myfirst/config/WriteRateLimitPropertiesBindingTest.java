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
 * 写限流配置绑定的边界：缺项、非正数都必须让容器起不来。
 *
 * 与 {@link AuthPropertiesBindingTest} 同一缘由：限流额度的兜底值在运行期无法观察——
 * 少了 {@code max} 会被读成 0、等于全站写请求一律 429；少了 {@code window} 则没有 TTL，
 * 计数键永不回收、该来源永久被封。两种都需要在启动阶段显性失败。
 */
class WriteRateLimitPropertiesBindingTest {

    private static final String[] COMPLETE = {
            "wanderchain.rate-limit.write.window=1m",
            "wanderchain.rate-limit.write.max=60",
            "wanderchain.rate-limit.write.unknown-max=20"
    };

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withUserConfiguration(BindingConfig.class)
            .withConfiguration(AutoConfigurations.of(
                    ConfigurationPropertiesAutoConfiguration.class,
                    ValidationAutoConfiguration.class));

    @Test
    @DisplayName("完整配置：绑定成功，三个值可直接使用")
    void bindsWhenComplete() {
        runner.withPropertyValues(COMPLETE).run(context -> {
            assertThat(context).hasNotFailed();
            WriteRateLimitProperties props = context.getBean(WriteRateLimitProperties.class);
            assertThat(props.window()).hasSeconds(60);
            assertThat(props.max()).isEqualTo(60);
            assertThat(props.unknownMax()).isEqualTo(20);
        });
    }

    @Test
    @DisplayName("缺 window：容器启动失败，而不是让计数键永不过期")
    void failsWhenWindowMissing() {
        runner.withPropertyValues(without("wanderchain.rate-limit.write.window=1m"))
                .run(context -> assertThat(context).hasFailed());
    }

    @Test
    @DisplayName("缺 max：容器启动失败，而不是把额度读成 0")
    void failsWhenMaxMissing() {
        runner.withPropertyValues(without("wanderchain.rate-limit.write.max=60"))
                .run(context -> assertThat(context).hasFailed());
    }

    @Test
    @DisplayName("缺 unknown-max：容器启动失败，而不是把未知来源额度读成 0")
    void failsWhenUnknownMaxMissing() {
        runner.withPropertyValues(without("wanderchain.rate-limit.write.unknown-max=20"))
                .run(context -> assertThat(context).hasFailed());
    }

    @Test
    @DisplayName("额度为 0：容器启动失败，而不是拒绝该来源的全部写请求")
    void failsWhenQuotaNotPositive() {
        runner.withPropertyValues(without("wanderchain.rate-limit.write.max=60"))
                .withPropertyValues("wanderchain.rate-limit.write.max=0")
                .run(context -> assertThat(context).hasFailed());
    }

    private static String[] without(String excluded) {
        return Arrays.stream(COMPLETE).filter(value -> !value.equals(excluded)).toArray(String[]::new);
    }

    @Configuration(proxyBeanMethods = false)
    @EnableConfigurationProperties(WriteRateLimitProperties.class)
    static class BindingConfig {
    }
}
