package com.luyi.myfirst.web.travel;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.luyi.myfirst.common.exception.BizException;
import com.luyi.myfirst.common.exception.TravelErrorEnum;
import com.luyi.myfirst.common.web.GlobalExceptionHandler;
import com.luyi.myfirst.service.travel.CurrencyService;
import com.luyi.myfirst.service.travel.WeatherService;
import com.luyi.myfirst.service.travel.model.CurrencyQuote;
import com.luyi.myfirst.service.travel.model.CurrencyTable;
import com.luyi.myfirst.service.travel.model.WeatherReading;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 两个旅行端点的响应形状与错误翻译（tasks 4.2 与 3.2 的验证）。
 *
 * 用 {@code standaloneSetup} 而不是启动整个应用：这里要守的是「字段集合 = 契约表」，
 * 与数据库、拦截器、认证都无关，起一个完整上下文只会把失败原因搅浑。
 *
 * 错误翻译把 {@link GlobalExceptionHandler} 一并挂上，因此「非法城市 → 400」
 * 与「上游不可用 → 502」走的是生产同一条路径，不是测试自己编的映射。
 */
class TravelControllerTest {

    private static final WeatherReading BEIJING_READING = new WeatherReading(
            "beijing", "Beijing", 9.0, "Clear", 5.0, Instant.parse("2026-11-11T17:15:00Z"));

    private static final CurrencyTable CURRENCY_TABLE = new CurrencyTable(
            "CNY",
            Instant.parse("2026-11-11T00:02:01Z"),
            List.of(
                    new CurrencyQuote("JPY", new BigDecimal("23.713927")),
                    new CurrencyQuote("USD", new BigDecimal("0.149528")),
                    new CurrencyQuote("EUR", new BigDecimal("0.1281")),
                    new CurrencyQuote("THB", new BigDecimal("5.009935")),
                    new CurrencyQuote("HKD", new BigDecimal("1.1631"))));

    private final ObjectMapper objectMapper = new ObjectMapper();

    private WeatherService weatherService;
    private CurrencyService currencyService;
    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        weatherService = mock(WeatherService.class);
        currencyService = mock(CurrencyService.class);
        mockMvc = MockMvcBuilders
                .standaloneSetup(new TravelController(weatherService, currencyService))
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();
    }

    @Test
    void weatherResponseExposesExactlyTheContractFields() throws Exception {
        given(weatherService.read("beijing")).willReturn(BEIJING_READING);

        MvcResult result = mockMvc.perform(get("/api/travel/weather").param("city", "beijing"))
                .andExpect(status().isOk())
                .andReturn();

        JsonNode body = objectMapper.readTree(result.getResponse().getContentAsString());
        assertThat(fieldNames(body)).containsExactlyInAnyOrder(
                "city", "cityName", "temperatureC", "condition", "windSpeedKph", "observedAt");
        assertThat(body.path("city").asText()).isEqualTo("beijing");
        assertThat(body.path("cityName").asText()).isEqualTo("Beijing");
        assertThat(body.path("temperatureC").asDouble()).isEqualTo(9.0);
        assertThat(body.path("condition").asText()).isEqualTo("Clear");
        assertThat(body.path("windSpeedKph").asDouble()).isEqualTo(5.0);
        assertThat(body.path("observedAt").asText()).isNotBlank();
    }

    @Test
    void currencyResponseExposesExactlyTheContractFieldsAndKeepsOrder() throws Exception {
        given(currencyService.read()).willReturn(CURRENCY_TABLE);

        MvcResult result = mockMvc.perform(get("/api/travel/currency"))
                .andExpect(status().isOk())
                .andReturn();

        JsonNode body = objectMapper.readTree(result.getResponse().getContentAsString());
        assertThat(fieldNames(body)).containsExactlyInAnyOrder("base", "updatedAt", "rates");
        assertThat(body.path("base").asText()).isEqualTo("CNY");
        assertThat(body.path("updatedAt").asText()).isNotBlank();

        JsonNode rates = body.path("rates");
        assertThat(rates).hasSize(5);
        assertThat(fieldNames(rates.get(0))).containsExactlyInAnyOrder("code", "value");

        List<String> codes = new ArrayList<>();
        rates.forEach(rate -> codes.add(rate.path("code").asText()));
        assertThat(codes).containsExactly("JPY", "USD", "EUR", "THB", "HKD");
    }

    @Test
    void rejectsUnknownCityAsBadRequestWithTheSharedErrorShape() throws Exception {
        given(weatherService.read("paris"))
                .willThrow(new BizException(TravelErrorEnum.TRAVEL_CITY_INVALID));

        mockMvc.perform(get("/api/travel/weather").param("city", "paris"))
                .andExpect(status().isBadRequest())
                // 与既有接口同形：code + message（这里只钉码，文案不属于契约）
                .andExpect(jsonPath("$.code").value("TRAVEL_CITY_INVALID"))
                .andExpect(jsonPath("$.message").isNotEmpty());
    }

    @Test
    void reportsUpstreamFailureAsBadGateway() throws Exception {
        given(weatherService.read("beijing"))
                .willThrow(new BizException(TravelErrorEnum.TRAVEL_WEATHER_UNAVAILABLE));

        mockMvc.perform(get("/api/travel/weather").param("city", "beijing"))
                .andExpect(status().isBadGateway())
                .andExpect(jsonPath("$.code").value("TRAVEL_WEATHER_UNAVAILABLE"));
    }

    private static List<String> fieldNames(JsonNode node) {
        List<String> names = new ArrayList<>();
        node.fieldNames().forEachRemaining(names::add);
        return names;
    }
}
