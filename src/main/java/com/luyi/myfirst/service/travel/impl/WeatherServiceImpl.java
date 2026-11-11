package com.luyi.myfirst.service.travel.impl;

import com.luyi.myfirst.common.exception.BizException;
import com.luyi.myfirst.common.exception.TravelErrorEnum;
import com.luyi.myfirst.config.TravelProperties;
import com.luyi.myfirst.service.travel.WeatherProvider;
import com.luyi.myfirst.service.travel.WeatherService;
import com.luyi.myfirst.service.travel.model.WeatherReading;
import com.luyi.myfirst.service.travel.support.TravelCache;
import org.springframework.stereotype.Service;

/**
 * 天气查询的业务实现。
 *
 * 三层职责划得很硬：白名单与默认值在这里，取数在 {@link WeatherProvider}，
 * 额度护栏在 {@link TravelCache}。任何一层单独变化都不牵动另外两层。
 *
 * 城市清单的来源是配置（{@code wanderchain.travel.cities}）而不是代码里的常量：
 * 「支持哪些城市」与「这些城市在哪」是同一件事，拆开维护迟早对不上。
 *
 * 缓存键带 {@code weather:} 前缀，与汇率共用同一个 {@link TravelCache} 时不会串号。
 */
@Service
public class WeatherServiceImpl implements WeatherService {

    /** 前端首次打开也默认北京（spec「首次打开默认北京」），后端保持一致，省略参数即可查。 */
    private static final String DEFAULT_CITY = "beijing";

    private final TravelProperties properties;
    private final WeatherProvider provider;
    private final TravelCache cache;

    public WeatherServiceImpl(TravelProperties properties, WeatherProvider provider, TravelCache cache) {
        this.properties = properties;
        this.provider = provider;
        this.cache = cache;
    }

    @Override
    public WeatherReading read(String citySlug) {
        String resolved = citySlug == null || citySlug.isBlank() ? DEFAULT_CITY : citySlug;
        TravelProperties.CityCoordinate city = properties.cities().get(resolved);
        if (city == null) {
            // 不回落到默认城市：请求了一个不存在的城市却拿到北京的天气，比明确报错更难排查
            throw new BizException(TravelErrorEnum.TRAVEL_CITY_INVALID);
        }
        return cache.get(TravelCache.WEATHER_PREFIX + resolved, properties.weatherCacheTtl(),
                () -> provider.read(resolved, city));
    }
}
