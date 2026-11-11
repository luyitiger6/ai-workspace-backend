package com.luyi.myfirst.service.travel.support;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * WMO 码到英文文案的映射（tasks 2.2）。
 *
 * 分段覆盖而不是逐码穷举：每个码段在展示上是同一个语义，逐码列举只会让新增码值时
 * 需要改更多断言，却不会多拦住任何真实缺陷。
 */
class WeatherConditionMapperTest {

    @Test
    void mapsEveryDocumentedSegment() {
        // 晴与云：0 与 1-3
        assertThat(WeatherConditionMapper.describe(0)).isEqualTo("Clear");
        assertThat(WeatherConditionMapper.describe(1)).isEqualTo("Mainly clear");
        assertThat(WeatherConditionMapper.describe(2)).isEqualTo("Partly cloudy");
        assertThat(WeatherConditionMapper.describe(3)).isEqualTo("Overcast");
        // 雾：45 / 48
        assertThat(WeatherConditionMapper.describe(45)).isEqualTo("Fog");
        assertThat(WeatherConditionMapper.describe(48)).isEqualTo("Depositing rime fog");
        // 毛毛雨：51-57
        assertThat(WeatherConditionMapper.describe(51)).isEqualTo("Light drizzle");
        assertThat(WeatherConditionMapper.describe(53)).isEqualTo("Moderate drizzle");
        assertThat(WeatherConditionMapper.describe(55)).isEqualTo("Dense drizzle");
        assertThat(WeatherConditionMapper.describe(56)).isEqualTo("Freezing drizzle");
        assertThat(WeatherConditionMapper.describe(57)).isEqualTo("Freezing drizzle");
        // 雨：61-67
        assertThat(WeatherConditionMapper.describe(61)).isEqualTo("Slight rain");
        assertThat(WeatherConditionMapper.describe(63)).isEqualTo("Moderate rain");
        assertThat(WeatherConditionMapper.describe(65)).isEqualTo("Heavy rain");
        assertThat(WeatherConditionMapper.describe(66)).isEqualTo("Freezing rain");
        assertThat(WeatherConditionMapper.describe(67)).isEqualTo("Freezing rain");
        // 雪：71-77
        assertThat(WeatherConditionMapper.describe(71)).isEqualTo("Slight snowfall");
        assertThat(WeatherConditionMapper.describe(73)).isEqualTo("Moderate snowfall");
        assertThat(WeatherConditionMapper.describe(75)).isEqualTo("Heavy snowfall");
        assertThat(WeatherConditionMapper.describe(77)).isEqualTo("Snow grains");
        // 阵雨：80-82
        assertThat(WeatherConditionMapper.describe(80)).isEqualTo("Slight rain showers");
        assertThat(WeatherConditionMapper.describe(81)).isEqualTo("Moderate rain showers");
        assertThat(WeatherConditionMapper.describe(82)).isEqualTo("Violent rain showers");
        // 阵雪：85-86
        assertThat(WeatherConditionMapper.describe(85)).isEqualTo("Slight snow showers");
        assertThat(WeatherConditionMapper.describe(86)).isEqualTo("Heavy snow showers");
        // 雷暴：95-99
        assertThat(WeatherConditionMapper.describe(95)).isEqualTo("Thunderstorm");
        assertThat(WeatherConditionMapper.describe(96)).isEqualTo("Thunderstorm with hail");
        assertThat(WeatherConditionMapper.describe(99)).isEqualTo("Thunderstorm with hail");
    }

    @Test
    void fallsBackToUnknownInsteadOfFailing() {
        // 数据源将来新增码值时，结果应该是「文案不够精确」，不该升级成「查询失败」
        assertThat(WeatherConditionMapper.describe(null)).isEqualTo("Unknown");
        assertThat(WeatherConditionMapper.describe(4)).isEqualTo("Unknown");
        assertThat(WeatherConditionMapper.describe(123)).isEqualTo("Unknown");
    }

    @Test
    void neverReturnsBlank() {
        for (int code = -10; code <= 200; code++) {
            assertThat(WeatherConditionMapper.describe(code)).isNotBlank();
        }
    }
}
