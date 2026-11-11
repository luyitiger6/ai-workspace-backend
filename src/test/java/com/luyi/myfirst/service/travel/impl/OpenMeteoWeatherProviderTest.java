package com.luyi.myfirst.service.travel.impl;

import com.luyi.myfirst.common.exception.BizException;
import com.luyi.myfirst.config.TravelProperties;
import com.luyi.myfirst.service.travel.model.WeatherReading;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import java.net.SocketTimeoutException;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withException;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

/**
 * Open-Meteo 适配层（tasks 2.1）。
 *
 * 三类失败必须归一到同一个业务异常：调用方与前端只需要知道「这次没拿到」，
 * 超时、上游 5xx、响应缺字段都走同一条失败路径。
 */
class OpenMeteoWeatherProviderTest {

    private static final TravelProperties.CityCoordinate BEIJING =
            new TravelProperties.CityCoordinate("Beijing", 39.9042, 116.4074);

    private MockRestServiceServer server;
    private OpenMeteoWeatherProvider provider;

    @BeforeEach
    void setUp() {
        RestClient.Builder builder = RestClient.builder().baseUrl("https://api.open-meteo.com");
        server = MockRestServiceServer.bindTo(builder).build();
        provider = new OpenMeteoWeatherProvider(builder.build());
    }

    @Test
    void parsesCurrentObservationByCoordinate() {
        server.expect(request -> {
            String query = request.getURI().getQuery();
            assertThat(query)
                    .contains("latitude=39.9042")
                    .contains("longitude=116.4074")
                    .contains("timezone=UTC")
                    .contains("temperature_2m");
        }).andRespond(withSuccess("""
                {
                  "current": {
                    "time": "2026-11-11T17:15",
                    "temperature_2m": 9.0,
                    "weather_code": 0,
                    "wind_speed_10m": 5.0
                  }
                }
                """, MediaType.APPLICATION_JSON));

        WeatherReading reading = provider.read("beijing", BEIJING);

        assertThat(reading.city()).isEqualTo("beijing");
        assertThat(reading.cityName()).isEqualTo("Beijing");
        assertThat(reading.temperatureC()).isEqualTo(9.0);
        assertThat(reading.condition()).isEqualTo("Clear");
        assertThat(reading.windSpeedKph()).isEqualTo(5.0);
        // 观测时刻按 UTC 解释：服务器时区不该改变它的含义
        assertThat(reading.observedAt()).isEqualTo(Instant.parse("2026-11-11T17:15:00Z"));
        server.verify();
    }

    @Test
    void mapsUpstreamServerErrorToBusinessException() {
        server.expect(request -> {
        }).andRespond(withServerError());

        assertThatThrownBy(() -> provider.read("beijing", BEIJING))
                .isInstanceOfSatisfying(BizException.class, e -> {
                    assertThat(e.getErrorCode()).isEqualTo("TRAVEL_WEATHER_UNAVAILABLE");
                    assertThat(e.getHttpStatus()).isEqualTo(HttpStatus.BAD_GATEWAY);
                });
    }

    @Test
    void mapsTimeoutToBusinessException() {
        server.expect(request -> {
        }).andRespond(withException(new SocketTimeoutException("read timed out")));

        assertThatThrownBy(() -> provider.read("beijing", BEIJING))
                .isInstanceOfSatisfying(BizException.class, e ->
                        assertThat(e.getHttpStatus()).isEqualTo(HttpStatus.BAD_GATEWAY));
    }

    @Test
    void mapsMissingFieldsToBusinessException() {
        server.expect(request -> {
        }).andRespond(withSuccess("""
                {"current": {"time": "2026-11-11T17:15"}}
                """, MediaType.APPLICATION_JSON));

        assertThatThrownBy(() -> provider.read("beijing", BEIJING))
                .isInstanceOfSatisfying(BizException.class, e ->
                        assertThat(e.getErrorCode()).isEqualTo("TRAVEL_WEATHER_UNAVAILABLE"));
    }
}
