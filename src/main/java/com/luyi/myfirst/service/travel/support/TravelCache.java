package com.luyi.myfirst.service.travel.support;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;

/**
 * 外部数据源结果的内存 TTL 缓存（design D3）。
 *
 * 放进程内存而不放 Redis，理由不是性能，是「缓存的目的」：这里缓存是为了不透支外部额度
 * （汇率按来源 IP 限流、天气有每日上限），不是为了让读变快。进程内存让单测不依赖 Redis，
 * 也让这份缓存故障时只影响自己，不会牵动景点榜单那条链路。
 *
 * 代价是「每个实例各存一份」：多实例部署时外部调用次数是实例数的倍数。当前是单实例演示，
 * 这个代价可接受；真要横向扩容时，换实现的落点是这里，调用方不用动。
 *
 * 不做防击穿：这里没有「回源很贵」的并发热点——同一个城市键在同一瞬间被两个请求同时
 * miss 的最坏结果是多打一次外部 API，而不是打穿数据库。为它引入互斥锁与等待，
 * 复杂度远大于收益。
 *
 * 键必须带前缀（{@link #WEATHER_PREFIX} / {@link #CURRENCY_PREFIX}）：两类数据的键空间
 * 合成一池，前缀是唯一能看出「这一条属于哪个数据源」的东西。
 */
@Component
public class TravelCache {

    /** 天气结果的键前缀，完整键形如 {@code weather:beijing}。 */
    public static final String WEATHER_PREFIX = "weather:";

    /** 汇率结果的键前缀，完整键形如 {@code currency:CNY}。 */
    public static final String CURRENCY_PREFIX = "currency:";

    private record Entry(Object value, Instant expiresAt) {

        boolean expiredAt(Instant now) {
            return !now.isBefore(expiresAt);
        }
    }

    private final ConcurrentHashMap<String, Entry> entries = new ConcurrentHashMap<>();

    /** 时间源可替换，单测据此把「过期」变成确定性事件，而不是 sleep 等待。 */
    private final Supplier<Instant> clock;

    @Autowired
    public TravelCache() {
        this(Instant::now);
    }

    TravelCache(Supplier<Instant> clock) {
        this.clock = clock;
    }

    /**
     * 命中且未过期就返回缓存值，否则回源并写入。
     *
     * 回源抛异常时不写缓存、异常原样抛出：把失败结果缓存下来会让一次短暂抖动
     * 变成持续一整个 TTL 的失败，而重试入口本来就是为了让用户立刻再试一次。
     *
     * @param key    带前缀的完整键
     * @param ttl    写入后的有效期
     * @param loader 回源逻辑
     * @return 缓存值或本次回源的值
     */
    public <T> T get(String key, Duration ttl, Supplier<T> loader) {
        Entry cached = entries.get(key);
        if (cached != null && !cached.expiredAt(clock.get())) {
            @SuppressWarnings("unchecked")
            T value = (T) cached.value();
            return value;
        }
        T loaded = loader.get();
        entries.put(key, new Entry(loaded, clock.get().plus(ttl)));
        return loaded;
    }
}
