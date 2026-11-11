package com.luyi.myfirst.service.travel.impl;

import com.luyi.myfirst.config.TravelProperties;
import com.luyi.myfirst.service.travel.CurrencyProvider;
import com.luyi.myfirst.service.travel.CurrencyService;
import com.luyi.myfirst.service.travel.model.CurrencyTable;
import com.luyi.myfirst.service.travel.support.TravelCache;
import org.springframework.stereotype.Service;

/**
 * 汇率查询的业务实现。
 *
 * 唯一职责是「套上缓存」——白名单在 provider 里（它只认五个币种的响应），
 * 币种顺序也在 provider 里（它按固定清单裁剪）。这里再加一层筛选就是重复。
 *
 * 缓存按 24 小时算，与数据源每天只更新一次对齐：缓存比数据源更新得慢，用户看到的是
 * 一份稳定的当日汇率；缓存比数据源更新得快，只会白白多打几次外部接口。
 *
 * 键里带基准币（{@code currency:CNY}）而不是一个光秃秃的 {@code currency}：
 * 将来真要支持别的基准币，键自然分化，不需要先改一遍缓存实现。
 */
@Service
public class CurrencyServiceImpl implements CurrencyService {

    private static final String CACHE_KEY = TravelCache.CURRENCY_PREFIX + "CNY";

    private final TravelProperties properties;
    private final CurrencyProvider provider;
    private final TravelCache cache;

    public CurrencyServiceImpl(TravelProperties properties, CurrencyProvider provider, TravelCache cache) {
        this.properties = properties;
        this.provider = provider;
        this.cache = cache;
    }

    @Override
    public CurrencyTable read() {
        return cache.get(CACHE_KEY, properties.currencyCacheTtl(), provider::read);
    }
}
