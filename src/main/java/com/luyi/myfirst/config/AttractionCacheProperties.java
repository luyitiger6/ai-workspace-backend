package com.luyi.myfirst.config;

import jakarta.validation.constraints.NotNull;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import java.time.Duration;

/**
 * 景点列表 / 榜单读缓存的可调参数（application.yml 的 wanderchain.attraction-cache）。
 *
 * 与 {@link AuthProperties}、{@link MediaProperties} 同样刻意不给代码内默认值：
 * 缺一项就绑定失败、启动即失败，避免「改了配置却因代码里有兜底而没生效」。
 *
 * 三项之间有一组硬约束，配反了防击穿会静默失效：
 *
 * - {@code lockTtl} 必须大于最坏情况下的回源耗时。锁在业务完成前过期，会被第二个请求
 *   抢走，同一键的回源就从一次变成多次。
 * - {@code waitTimeout} 必须小于 {@code lockTtl}。等待者比锁活得久，等到的必然是锁
 *   已经消失、缓存却仍未写入的空窗，纯粹白等。
 *
 * @param ttl         缓存有效期，即列表与榜单结果可复用的最大陈旧窗口
 * @param lockTtl     回源互斥锁的存活时间
 * @param waitTimeout 未抢到锁的请求等待缓存就绪的上限；耗尽后改为直接查库（不回写）
 */
@Validated
@ConfigurationProperties(prefix = "wanderchain.attraction-cache")
public record AttractionCacheProperties(
        @NotNull Duration ttl,
        @NotNull Duration lockTtl,
        @NotNull Duration waitTimeout
) {
}
