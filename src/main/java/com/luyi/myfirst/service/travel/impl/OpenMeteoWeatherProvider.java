package com.luyi.myfirst.service.travel.impl;

import com.fasterxml.jackson.databind.JsonNode;
import com.luyi.myfirst.common.exception.BizException;
import com.luyi.myfirst.common.exception.TravelErrorEnum;
import com.luyi.myfirst.config.TravelProperties;
import com.luyi.myfirst.service.travel.WeatherProvider;
import com.luyi.myfirst.service.travel.model.WeatherReading;
import com.luyi.myfirst.service.travel.support.WeatherConditionMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeParseException;

/**
 * Open-Meteo 的天气读取实现（design D2 的当前落点）。
 *
 * 选它而不是 OpenWeatherMap，是因为它不需要凭据：本次要验证的是「头部快速查询」这条链路
 * 本身，不该被一个尚未激活的 key 挡住。换源的代价被 {@code WeatherProvider} 接口吸收。
 *
 * 用 {@code JsonNode} 而不是自定义响应 record：外部数据源的字段集合不由我们控制，
 * 我们只取三个字段。把整份响应映射成 record 等于替对方维护一份随时会过时的契约，
 * 且首次绑定失败时排查成本更高。
 *
 * 超时是必须项：外部服务变慢时，请求要显性失败并让前端给出重试入口，
 * 而不是把 Tomcat 线程挂在上游等它。超时在 {@code TravelClientConfig} 里随客户端一起配置，
 * 这里只负责取数据。
 */
@Component
public class OpenMeteoWeatherProvider implements WeatherProvider {

    private static final Logger log = LoggerFactory.getLogger(OpenMeteoWeatherProvider.class);

    /** 只要当前观测的三项，不要预报与历史：少取一项就少一处可能缺失的字段。 */
    private static final String CURRENT_FIELDS = "temperature_2m,weather_code,wind_speed_10m";

    private final RestClient client;

    public OpenMeteoWeatherProvider(@Qualifier("weatherRestClient") RestClient client) {
        this.client = client;
    }

    @Override
    public WeatherReading read(String citySlug, TravelProperties.CityCoordinate city) {
        JsonNode current = fetch(citySlug, city).path("current");
        if (!current.path("temperature_2m").isNumber() || !current.path("wind_speed_10m").isNumber()) {
            throw fail(citySlug, "current fields missing");
        }
        return new WeatherReading(
                citySlug,
                city.nameEn(),
                current.path("temperature_2m").asDouble(),
                WeatherConditionMapper.describe(current.path("weather_code").isNumber()
                        ? current.path("weather_code").asInt()
                        : null),
                current.path("wind_speed_10m").asDouble(),
                parseObservedAt(current.path("time").asText(null), citySlug));
    }

    private JsonNode fetch(String citySlug, TravelProperties.CityCoordinate city) {
        try {
            JsonNode root = client.get()
                    .uri(builder -> builder.path("/v1/forecast")
                            .queryParam("latitude", city.latitude())
                            .queryParam("longitude", city.longitude())
                            .queryParam("current", CURRENT_FIELDS)
                            .queryParam("timezone", "UTC")
                            .build())
                    .retrieve()
                    .body(JsonNode.class);
            if (root == null) {
                throw fail(citySlug, "empty body");
            }
            return root;
        } catch (RestClientException e) {
            // 非 2xx、连接失败、超时都归到这里：调用方不需要区分，只有日志需要
            throw fail(citySlug, e.getClass().getSimpleName());
        }
    }

    /**
     * {@code current.time} 是「不带时区的本地时刻」，请求里已把时区钉成 UTC，
     * 因此直接按 UTC 解释。不能用本地默认时区：服务器的时区不该改变一条观测时刻的含义。
     */
    private Instant parseObservedAt(String raw, String citySlug) {
        if (raw == null || raw.isBlank()) {
            throw fail(citySlug, "current time missing");
        }
        try {
            return LocalDateTime.parse(raw).toInstant(ZoneOffset.UTC);
        } catch (DateTimeParseException e) {
            throw fail(citySlug, "current time unparsable");
        }
    }

    private static BizException fail(String citySlug, String reason) {
        log.warn("open-meteo read failed, city={}, reason={}", citySlug, reason);
        return new BizException(TravelErrorEnum.TRAVEL_WEATHER_UNAVAILABLE);
    }
}
