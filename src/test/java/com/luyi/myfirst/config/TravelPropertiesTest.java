package com.luyi.myfirst.config;

import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.context.ConfigurationPropertiesAutoConfiguration;
import org.springframework.boot.autoconfigure.validation.ValidationAutoConfiguration;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 配置绑定契约：缺任一必需项时启动失败（design D5）。
 *
 * 项目里四处配置类都用同一套 @Validated + @NotNull，这条测试守的是「不再给代码内默认值」
 * 这条口径本身，而不是某个字段。
 */
class TravelPropertiesTest {

    private static final String[] REQUIRED = {
            "wanderchain.travel.weather-base-url=https://api.open-meteo.com",
            "wanderchain.travel.currency-base-url=https://open.er-api.com",
            "wanderchain.travel.connect-timeout=2s",
            "wanderchain.travel.read-timeout=4s",
            "wanderchain.travel.weather-cache-ttl=30m",
            "wanderchain.travel.currency-cache-ttl=24h",
    };

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(
                    ConfigurationPropertiesAutoConfiguration.class,
                    ValidationAutoConfiguration.class))
            .withUserConfiguration(PropertiesConfig.class);

    @Test
    void bindsWhenEveryRequiredItemAndCityPresent() {
        runner.withPropertyValues(concat(REQUIRED,
                "wanderchain.travel.cities.beijing.name-en=Beijing",
                "wanderchain.travel.cities.beijing.latitude=39.9042",
                "wanderchain.travel.cities.beijing.longitude=116.4074"))
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context.getBean(TravelProperties.class).cities()).containsKey("beijing");
                });
    }

    @Test
    void failsWhenCitiesTableIsEmpty() {
        runner.withPropertyValues(REQUIRED)
                .run(context -> assertThat(context).hasFailed());
    }

    @Test
    void failsWhenCurrencyCacheTtlMissing() {
        // 故意不给 currency-cache-ttl：其余五项齐全，只缺这一项。
        runner.withPropertyValues(
                "wanderchain.travel.weather-base-url=https://api.open-meteo.com",
                "wanderchain.travel.currency-base-url=https://open.er-api.com",
                "wanderchain.travel.connect-timeout=2s",
                "wanderchain.travel.read-timeout=4s",
                "wanderchain.travel.weather-cache-ttl=30m",
                "wanderchain.travel.cities.beijing.name-en=Beijing",
                "wanderchain.travel.cities.beijing.latitude=39.9042",
                "wanderchain.travel.cities.beijing.longitude=116.4074")
                .run(context -> assertThat(context).hasFailed());
    }

    private static String[] concat(String[] base, String... extra) {
        String[] merged = new String[base.length + extra.length];
        System.arraycopy(base, 0, merged, 0, base.length);
        System.arraycopy(extra, 0, merged, base.length, extra.length);
        return merged;
    }

    @Configuration
    @EnableConfigurationProperties(TravelProperties.class)
    static class PropertiesConfig {
    }
}
