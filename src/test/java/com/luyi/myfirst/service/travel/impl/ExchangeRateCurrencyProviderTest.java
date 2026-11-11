package com.luyi.myfirst.service.travel.impl;

import com.luyi.myfirst.common.exception.BizException;
import com.luyi.myfirst.service.travel.model.CurrencyQuote;
import com.luyi.myfirst.service.travel.model.CurrencyTable;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

/**
 * ExchangeRate-API 适配层（tasks 2.3）。
 *
 * 裁剪与顺序都在这里钉死：数据源给上百个币种，展示哪五个是 spec 的决定，
 * 不该留到前端再筛一遍。
 */
class ExchangeRateCurrencyProviderTest {

    private MockRestServiceServer server;
    private ExchangeRateCurrencyProvider provider;

    @BeforeEach
    void setUp() {
        RestClient.Builder builder = RestClient.builder().baseUrl("https://open.er-api.com");
        server = MockRestServiceServer.bindTo(builder).build();
        provider = new ExchangeRateCurrencyProvider(builder.build());
    }

    @Test
    void keepsOnlyTheFiveSpecifiedCodesInOrder() {
        server.expect(request -> assertThat(request.getURI().getPath()).isEqualTo("/v6/latest/CNY"))
                .andRespond(withSuccess("""
                        {
                          "result": "success",
                          "time_last_update_unix": 1762819201,
                          "base_code": "CNY",
                          "rates": {
                            "CNY": 1,
                            "USD": 0.149528,
                            "EUR": 0.1281,
                            "JPY": 23.713927,
                            "GBP": 0.11,
                            "THB": 5.009935,
                            "HKD": 1.1631,
                            "KRW": 190.5
                          }
                        }
                        """, MediaType.APPLICATION_JSON));

        CurrencyTable table = provider.read();

        assertThat(table.base()).isEqualTo("CNY");
        assertThat(table.updatedAt()).isEqualTo(Instant.ofEpochSecond(1762819201));
        // GBP / KRW / CNY 都在上游响应里，但 spec 只认五个
        assertThat(table.rates()).extracting(CurrencyQuote::code)
                .containsExactly("JPY", "USD", "EUR", "THB", "HKD");
        assertThat(table.rates().get(0).value()).isEqualByComparingTo("23.713927");
        server.verify();
    }

    @Test
    void failsWhenAnyOfTheFiveIsMissing() {
        // 少 HKD
        server.expect(request -> {
        }).andRespond(withSuccess("""
                {
                  "result": "success",
                  "time_last_update_unix": 1762819201,
                  "rates": {"JPY": 23.7, "USD": 0.15, "EUR": 0.13, "THB": 5.0}
                }
                """, MediaType.APPLICATION_JSON));

        assertThatThrownBy(provider::read)
                .isInstanceOfSatisfying(BizException.class, e ->
                        assertThat(e.getErrorCode()).isEqualTo("TRAVEL_CURRENCY_UNAVAILABLE"));
    }

    @Test
    void failsWhenResultIsNotSuccess() {
        // 数据源用 200 + result 表达拒绝，不能只看 HTTP 状态
        server.expect(request -> {
        }).andRespond(withSuccess("""
                {"result": "error", "error-type": "quota-exceeded"}
                """, MediaType.APPLICATION_JSON));

        assertThatThrownBy(provider::read)
                .isInstanceOfSatisfying(BizException.class, e ->
                        assertThat(e.getHttpStatus()).isEqualTo(HttpStatus.BAD_GATEWAY));
    }
}
