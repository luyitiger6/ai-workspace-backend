package com.luyi.myfirst.service.travel;

import com.luyi.myfirst.service.travel.model.WeatherReading;

/**
 * 天气查询的入口（spec「天气查询的城市范围与默认值」）。
 *
 * 城市白名单、默认值、缓存都在这一层，{@link WeatherProvider} 只负责「给我坐标，我取数据」。
 * 这样换数据源不会碰到业务规则，改业务规则也不会碰到数据源适配代码。
 */
public interface WeatherService {

    /**
     * 读取一个城市的当前天气。
     *
     * @param citySlug 城市 slug；null 或空白按 {@code beijing} 处理（与前端首次打开默认一致），
     *                 其余不在配置名单内的值一律拒绝
     * @return 当前天气；外部数据源失败时抛业务异常，不返回 null
     */
    WeatherReading read(String citySlug);
}
