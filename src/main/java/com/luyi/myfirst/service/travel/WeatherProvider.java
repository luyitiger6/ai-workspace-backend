package com.luyi.myfirst.service.travel;

import com.luyi.myfirst.config.TravelProperties;
import com.luyi.myfirst.service.travel.model.WeatherReading;

/**
 * 天气数据源的抽象（design D2）。
 *
 * 存在的理由是「换源不换壳」：当前实现走 Open-Meteo（无需凭据），而 OpenWeatherMap 的
 * key 一旦生效，只需新增一个实现、改一处装配，控制器 / DTO / 缓存 / 前端全部不动。
 *
 * 入参收坐标而不是城市名：城市名的写法有歧义（同名地点不止一个），而坐标是唯一确定的，
 * 且避开了带撇号城市名的 URL 编码问题。
 *
 * 实现必须把「拿不到可用数据」表达为 {@code BizException}，不要返回 null 或半成品：
 * 调用方据此统一转成失败态，前端据此展示失败提示与重试入口，不需要区分失败原因。
 */
public interface WeatherProvider {

    /**
     * 读取一个城市的当前天气。
     *
     * @param citySlug 城市 slug，仅用于错误日志定位
     * @param city     配置里该城市的展示名与坐标
     * @return 当前天气，必然包含温度、状况文案、风速与观测时刻
     */
    WeatherReading read(String citySlug, TravelProperties.CityCoordinate city);
}
