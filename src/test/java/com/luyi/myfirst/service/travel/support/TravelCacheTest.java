package com.luyi.myfirst.service.travel.support;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 内存 TTL 缓存（tasks 3.1）。
 *
 * 时间源在这里被替换成可控引用，因此「过期」是一个确定性事件，而不是 sleep 出来的近似值。
 */
class TravelCacheTest {

    private static final Instant T0 = Instant.parse("2026-11-11T00:00:00Z");

    @Test
    void servesCachedValueBeforeTtl() {
        AtomicReference<Instant> now = new AtomicReference<>(T0);
        TravelCache cache = new TravelCache(now::get);
        AtomicInteger loads = new AtomicInteger();
        Supplier<String> loader = () -> "v" + loads.incrementAndGet();

        assertThat(cache.get("weather:beijing", Duration.ofMinutes(30), loader)).isEqualTo("v1");

        // 差一秒到期：仍然命中
        now.set(T0.plusSeconds(30 * 60 - 1));
        assertThat(cache.get("weather:beijing", Duration.ofMinutes(30), loader)).isEqualTo("v1");
        assertThat(loads.get()).isEqualTo(1);
    }

    @Test
    void reloadsOnceTtlIsReached() {
        AtomicReference<Instant> now = new AtomicReference<>(T0);
        TravelCache cache = new TravelCache(now::get);
        AtomicInteger loads = new AtomicInteger();
        Supplier<String> loader = () -> "v" + loads.incrementAndGet();

        cache.get("weather:beijing", Duration.ofMinutes(30), loader);

        // 恰好到期即视为过期：有效期是左闭右开区间，不是「多给一点」
        now.set(T0.plusSeconds(30 * 60));
        assertThat(cache.get("weather:beijing", Duration.ofMinutes(30), loader)).isEqualTo("v2");
        assertThat(loads.get()).isEqualTo(2);
    }

    @Test
    void keepsPrefixesApart() {
        TravelCache cache = new TravelCache(() -> T0);

        cache.get(TravelCache.WEATHER_PREFIX + "beijing", Duration.ofMinutes(30), () -> "sunny");
        cache.get(TravelCache.CURRENCY_PREFIX + "CNY", Duration.ofHours(24), () -> "rates");

        assertThat(cache.get(TravelCache.WEATHER_PREFIX + "beijing", Duration.ofMinutes(30), () -> "other"))
                .isEqualTo("sunny");
        assertThat(cache.get(TravelCache.CURRENCY_PREFIX + "CNY", Duration.ofHours(24), () -> "other"))
                .isEqualTo("rates");
    }

    @Test
    void doesNotCacheFailure() {
        TravelCache cache = new TravelCache(() -> T0);

        assertThatThrownBy(() -> cache.get("currency:CNY", Duration.ofHours(24), () -> {
            throw new IllegalStateException("upstream down");
        })).isInstanceOf(IllegalStateException.class);

        // 失败没有被写进缓存：下一次仍然回源，并能在恢复后立刻拿到结果
        assertThat(cache.get("currency:CNY", Duration.ofHours(24), () -> "ok")).isEqualTo("ok");
    }

    @Test
    void survivesConcurrentAccess() throws Exception {
        TravelCache cache = new TravelCache();
        ExecutorService pool = Executors.newFixedThreadPool(8);
        try {
            for (int i = 0; i < 500; i++) {
                String key = "weather:city" + (i % 4);
                pool.submit(() -> cache.get(key, Duration.ofMillis(1), () -> "v"));
            }
            pool.shutdown();
            assertThat(pool.awaitTermination(5, TimeUnit.SECONDS)).isTrue();
        } finally {
            pool.shutdownNow();
        }
    }
}
