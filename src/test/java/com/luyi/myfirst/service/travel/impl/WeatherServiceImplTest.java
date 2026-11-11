package com.luyi.myfirst.service.travel.impl;

import com.luyi.myfirst.common.exception.BizException;
import com.luyi.myfirst.config.TravelProperties;
import com.luyi.myfirst.service.travel.model.WeatherReading;
import com.luyi.myfirst.service.travel.support.TravelCache;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 天气业务层（tasks 3.3）。
 *
 * 这一层要守的是「规则」而不是「取数」：白名单、默认值、缓存命中，都不该依赖真实网络。
 */
class WeatherServiceImplTest {

    private static final WeatherReading BEIJING_READING = new WeatherReading(
            "beijing", "Beijing", 9.0, "Clear", 5.0, Instant.parse("2026-11-11T17:15:00Z"));

    private static TravelProperties properties(Duration weatherTtl) {
        return new TravelProperties(
                "https://api.open-meteo.com",
                "https://open.er-api.com",
                Duration.ofSeconds(2),
                Duration.ofSeconds(4),
                weatherTtl,
                Duration.ofHours(24),
                Map.of(
                        "beijing", new TravelProperties.CityCoordinate("Beijing", 39.9042, 116.4074),
                        "chengdu", new TravelProperties.CityCoordinate("Chengdu", 30.5728, 104.0668)));
    }

    @Test
    void servesSecondCallFromCache() {
        AtomicInteger calls = new AtomicInteger();
        WeatherServiceImpl service = new WeatherServiceImpl(
                properties(Duration.ofMinutes(30)),
                (slug, city) -> {
                    calls.incrementAndGet();
                    return BEIJING_READING;
                },
                new TravelCache());

        service.read("beijing");
        service.read("beijing");

        assertThat(calls.get()).isEqualTo(1);
    }

    @Test
    void defaultsToBeijingWhenSlugOmitted() {
        AtomicReference<String> requested = new AtomicReference<>();
        WeatherServiceImpl service = new WeatherServiceImpl(
                properties(Duration.ofMinutes(30)),
                (slug, city) -> {
                    requested.set(slug);
                    return BEIJING_READING;
                },
                new TravelCache());

        assertThat(service.read(null)).isSameAs(BEIJING_READING);
        assertThat(service.read("  ")).isSameAs(BEIJING_READING);
        assertThat(requested.get()).isEqualTo("beijing");
    }

    @Test
    void rejectsSlugOutsideTheConfiguredList() {
        AtomicInteger calls = new AtomicInteger();
        WeatherServiceImpl service = new WeatherServiceImpl(
                properties(Duration.ofMinutes(30)),
                (slug, city) -> {
                    calls.incrementAndGet();
                    return BEIJING_READING;
                },
                new TravelCache());

        assertThatThrownBy(() -> service.read("paris"))
                .isInstanceOfSatisfying(BizException.class, e ->
                        assertThat(e.getErrorCode()).isEqualTo("TRAVEL_CITY_INVALID"));
        // 白名单之外的城市不该触发任何外部调用
        assertThat(calls.get()).isZero();
    }

    @Test
    void propagatesProviderFailureAsBusinessException() {
        WeatherServiceImpl service = new WeatherServiceImpl(
                properties(Duration.ofMinutes(30)),
                (slug, city) -> {
                    throw new BizException(com.luyi.myfirst.common.exception.TravelErrorEnum.TRAVEL_WEATHER_UNAVAILABLE);
                },
                new TravelCache());

        assertThatThrownBy(() -> service.read("beijing"))
                .isInstanceOfSatisfying(BizException.class, e ->
                        assertThat(e.getErrorCode()).isEqualTo("TRAVEL_WEATHER_UNAVAILABLE"));
    }

    @Test
    void reloadsAfterWeatherTtlElapsed() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        WeatherServiceImpl service = new WeatherServiceImpl(
                properties(Duration.ofMillis(30)),
                (slug, city) -> {
                    calls.incrementAndGet();
                    return BEIJING_READING;
                },
                new TravelCache());

        service.read("beijing");
        Thread.sleep(50);
        service.read("beijing");

        assertThat(calls.get()).isEqualTo(2);
    }
}
