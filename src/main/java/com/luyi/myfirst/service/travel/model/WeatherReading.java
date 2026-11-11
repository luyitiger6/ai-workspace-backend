package com.luyi.myfirst.service.travel.model;

import java.time.Instant;

/**
 * 一次天气读取的结果（spec「天气结果的展示内容」）。
 *
 * {@code city} 是请求里的城市 slug，{@code cityName} 是配置提供的英文展示名：
 * 两者都要留着，前端用前者维持下拉选中态、用后者展示，不必再自己维护一张 slug 到名字的表。
 *
 * {@code condition} 已经是可展示的英文文案而不是 WMO 数值码：数值码的翻译规则属于
 * 服务端支撑件，前端不该为了显示一句话再复制一份码表。
 *
 * {@code observedAt} 是数据源给的观测时刻，不是我们收到响应的时刻——新鲜度标注要如实反映
 * 数据有多旧，用本地时钟会把它算成「刚刚」。
 */
public record WeatherReading(
        String city,
        String cityName,
        double temperatureC,
        String condition,
        double windSpeedKph,
        Instant observedAt
) {
}
