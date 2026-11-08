package com.luyi.myfirst.config;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import java.time.Duration;

/**
 * 写操作来源限流的可调参数（application.yml 的 wanderchain.rate-limit.write）。
 *
 * 与 {@link AuthProperties}、{@link MediaProperties}、{@link AttractionCacheProperties}
 * 同样刻意不给代码内默认值：缺一项就绑定失败、启动即失败，避免「改了配置却因代码里有
 * 兜底而没生效」。
 *
 * 三个参数的分工：
 *
 * - {@code window} 固定窗口长度，同时是计数键的 TTL。窗口从该来源的第一次写请求起算，
 *   不与自然分钟对齐：对齐的话，边界两侧的突发会被切成两个窗口，实际额度接近翻倍。
 * - {@code max} 单个已解析来源在窗口内的写请求上限。
 * - {@code unknownMax} 全部未知来源共用的独立额度。它应当小于 {@code max}：代理配置
 *   异常时所有请求都会落进这一个桶，额度越小，误配的影响面越小。这条约束不在代码里
 *   强制（与 {@link AttractionCacheProperties} 的 lockTtl / waitTimeout 同一处理）——
 *   运行时没有任何可观察的异常，靠配置评审拦截。
 *
 * @param window     固定窗口长度，也是计数键的 TTL
 * @param max        已解析来源的窗口内额度
 * @param unknownMax 未知来源共用的窗口内额度
 */
@Validated
@ConfigurationProperties(prefix = "wanderchain.rate-limit.write")
public record WriteRateLimitProperties(
        @NotNull Duration window,
        @Min(1) int max,
        @Min(1) int unknownMax
) {
}
