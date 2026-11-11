package com.luyi.myfirst.service.travel.impl;

import com.luyi.myfirst.config.TravelProperties;
import com.luyi.myfirst.service.travel.model.CurrencyQuote;
import com.luyi.myfirst.service.travel.model.CurrencyTable;
import com.luyi.myfirst.service.travel.support.TravelCache;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 汇率业务层（tasks 3.4）。
 *
 * 这一层只做缓存，不做筛选：币种裁剪与顺序由 provider 负责，这里断言的是「没有被重排」。
 */
class CurrencyServiceImplTest {

    private static final CurrencyTable TABLE = new CurrencyTable(
            "CNY",
            Instant.parse("2026-11-11T00:02:01Z"),
            List.of(
                    new CurrencyQuote("JPY", new BigDecimal("23.713927")),
                    new CurrencyQuote("USD", new BigDecimal("0.149528")),
                    new CurrencyQuote("EUR", new BigDecimal("0.1281")),
                    new CurrencyQuote("THB", new BigDecimal("5.009935")),
                    new CurrencyQuote("HKD", new BigDecimal("1.1631"))));

    private static TravelProperties properties(Duration currencyTtl) {
        return new TravelProperties(
                "https://api.open-meteo.com",
                "https://open.er-api.com",
                Duration.ofSeconds(2),
                Duration.ofSeconds(4),
                Duration.ofMinutes(30),
                currencyTtl,
                Map.of("beijing", new TravelProperties.CityCoordinate("Beijing", 39.9042, 116.4074)));
    }

    @Test
    void doesNotCallProviderTwiceWithinCacheWindow() {
        AtomicInteger calls = new AtomicInteger();
        CurrencyServiceImpl service = new CurrencyServiceImpl(
                properties(Duration.ofHours(24)),
                () -> {
                    calls.incrementAndGet();
                    return TABLE;
                },
                new TravelCache());

        service.read();
        service.read();

        assertThat(calls.get()).isEqualTo(1);
    }

    @Test
    void keepsProviderOrderUntouched() {
        CurrencyServiceImpl service = new CurrencyServiceImpl(
                properties(Duration.ofHours(24)),
                () -> TABLE,
                new TravelCache());

        assertThat(service.read().rates()).extracting(CurrencyQuote::code)
                .containsExactly("JPY", "USD", "EUR", "THB", "HKD");
        assertThat(service.read().base()).isEqualTo("CNY");
    }
}
