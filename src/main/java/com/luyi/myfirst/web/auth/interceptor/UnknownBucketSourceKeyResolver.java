package com.luyi.myfirst.web.auth.interceptor;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.stereotype.Component;

/**
 * 当前的来源键策略：所有请求归入单一「未知来源」桶。
 *
 * 这是 tasks 5.4 的接线点，不是最终形态。可信代理链的解析（只采信已登记代理层
 * 追加的那一跳、直达请求的转发头不采信、取不到时归未知桶、IPv6 按 /64 聚合）
 * 属 5.9，届时替换本类而不动调用方。
 *
 * 为什么现在不拿 {@code getRemoteAddr()} 顶上：浏览器请求先到 Next.js 的
 * {@code /api/[...path]} 再转发给后端，那里拿到的是 Next server 的地址——
 * 看起来是「按来源限流」，实际是全站共用一个桶，一天都发现不了。
 *
 * 代价是明确的：在 5.9 落地前，任何来源的失败都会计入同一个桶，
 * 限流会比真实情况更早触发（fail-closed）。宁可误伤，不可放过。
 */
@Component
public class UnknownBucketSourceKeyResolver implements SourceKeyResolver {

    /** 未知来源桶的键。测试据它构造「来源已被限流」的场景。 */
    public static final String UNKNOWN_SOURCE = "unknown";

    @Override
    public String resolve(HttpServletRequest request) {
        return UNKNOWN_SOURCE;
    }
}
