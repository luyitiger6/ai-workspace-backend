package com.luyi.myfirst.service.travel;

import com.luyi.myfirst.service.travel.model.CurrencyTable;

/**
 * 汇率查询的入口（spec「汇率查询的币种范围」）。
 *
 * 没有入参：spec 把「1 CNY 为基准、固定五个币种」钉死成唯一形态，开放参数只会让
 * 未定义的用法从接口上长出来。
 */
public interface CurrencyService {

    /**
     * 读取以 CNY 为基准的汇率表。
     *
     * @return 基准币、数据源声明的更新时刻，以及固定顺序的五个币种
     */
    CurrencyTable read();
}
