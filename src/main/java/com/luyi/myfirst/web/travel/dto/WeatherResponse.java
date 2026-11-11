package com.luyi.myfirst.web.travel.dto;

import com.luyi.myfirst.common.web.ApiResponse;

import java.time.Instant;

/**
 * 天气端点的响应体（spec「天气结果的展示内容」）。
 *
 * {@code city} 与 {@code cityName} 同时返回：前端用前者维持下拉选中态，用后者展示，
 * 不必再自己维护一张 slug 到展示名的表。
 *
 * {@code condition} 已经是英文文案，不是 WMO 数值码——码表属于数据源的语言，
 * 翻译规则留在服务端，前端不复制第二份。
 *
 * {@code observedAt} 用 {@code Instant}（→ ISO 字符串），承载数据源声明的观测时刻而不是
 * 我们取到响应的时刻：新鲜度标注要如实反映数据有多旧。
 *
 * 与前端 {@code frontend/lib/contracts/travel.ts} 的同名 interface 双向比对，
 * 由 10 号闸门负责：改这里必须同步改那一侧。
 */
public record WeatherResponse(
        String city,
        String cityName,
        double temperatureC,
        String condition,
        double windSpeedKph,
        Instant observedAt
) implements ApiResponse {
}
