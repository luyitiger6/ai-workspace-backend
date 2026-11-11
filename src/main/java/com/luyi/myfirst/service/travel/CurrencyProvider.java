package com.luyi.myfirst.service.travel;

import com.luyi.myfirst.service.travel.model.CurrencyTable;

/**
 * 汇率数据源的抽象（design D2）。
 *
 * 不接收基准币参数：spec 把「1 CNY 为基准」钉死成唯一形态，开放一个入参只会让
 * 「换个币种看看」这种未定义需求从接口上长出来。将来真要支持别的基准币，
 * 那是新的 spec 与新的入参，不是这里预留的口子。
 *
 * 实现必须把「拿不到可用数据」表达为 {@code BizException}；返回一张少了几个币种的表
 * 同样算失败——前端按固定五币种渲染，缺项会变成空白行，正是 spec 禁止的空表表现。
 */
public interface CurrencyProvider {

    /**
     * 读取以 CNY 为基准的汇率表。
     *
     * @return 基准币、数据源声明的更新时刻，以及固定五个币种的汇率
     */
    CurrencyTable read();
}
