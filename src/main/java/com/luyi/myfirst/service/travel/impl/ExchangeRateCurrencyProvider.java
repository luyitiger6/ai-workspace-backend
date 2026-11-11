package com.luyi.myfirst.service.travel.impl;

import com.fasterxml.jackson.databind.JsonNode;
import com.luyi.myfirst.common.exception.BizException;
import com.luyi.myfirst.common.exception.TravelErrorEnum;
import com.luyi.myfirst.config.TravelProperties;
import com.luyi.myfirst.service.travel.CurrencyProvider;
import com.luyi.myfirst.service.travel.model.CurrencyQuote;
import com.luyi.myfirst.service.travel.model.CurrencyTable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * ExchangeRate-API 开放端点的汇率读取实现（design D2 的当前落点）。
 *
 * 走的是不需要 key 的 open access 端点（{@code /v6/latest/CNY}）：它有两条硬约束，
 * 都影响本实现——每天只更新一次（因此缓存按天算，且新鲜度按数据源声明的时间标注），
 * 以及按来源 IP 限流（因此必须服务端缓存，不能每个访客都打一次）。
 *
 * 只取 spec 钉死的五个币种。数据源返回上百个，全量透传会把「展示哪几个」的决策
 * 推到前端，将来两边各改一半必然对不上。
 *
 * 缺任一个币种即整体失败，而不是返回残缺的表：前端按固定五币种渲染，
 * 少一项就是一个空白行，正是 spec 禁止的空表表现。
 */
@Component
public class ExchangeRateCurrencyProvider implements CurrencyProvider {

    private static final Logger log = LoggerFactory.getLogger(ExchangeRateCurrencyProvider.class);

    /** spec「汇率查询的币种范围」钉死的五个币种，顺序即展示顺序。 */
    private static final List<String> QUOTED_CODES = List.of("JPY", "USD", "EUR", "THB", "HKD");

    /** spec 把「1 CNY 为基准」钉死成唯一形态。 */
    private static final String BASE_CURRENCY = "CNY";

    private final RestClient client;

    public ExchangeRateCurrencyProvider(@Qualifier("currencyRestClient") RestClient client) {
        this.client = client;
    }

    @Override
    public CurrencyTable read() {
        JsonNode root = fetch();
        String result = root.path("result").asText("");
        if (!"success".equals(result)) {
            // 数据源用 200 + result 表达拒绝（例如额度耗尽），不能只看 HTTP 状态
            throw fail("result=" + result);
        }

        JsonNode rates = root.path("rates");
        List<CurrencyQuote> quotes = new ArrayList<>(QUOTED_CODES.size());
        for (String code : QUOTED_CODES) {
            JsonNode value = rates.path(code);
            if (!value.isNumber()) {
                throw fail("rate missing: " + code);
            }
            quotes.add(new CurrencyQuote(code, value.decimalValue()));
        }
        return new CurrencyTable(BASE_CURRENCY, parseUpdatedAt(root.path("time_last_update_unix")), List.copyOf(quotes));
    }

    private JsonNode fetch() {
        try {
            JsonNode root = client.get()
                    .uri(builder -> builder.path("/v6/latest/" + BASE_CURRENCY).build())
                    .retrieve()
                    .body(JsonNode.class);
            if (root == null) {
                throw fail("empty body");
            }
            return root;
        } catch (RestClientException e) {
            throw fail(e.getClass().getSimpleName());
        }
    }

    /**
     * 用数据源声明的最后更新时刻，不用本地拉取时刻：数据源每天只更新一次，
     * 用本地时刻标注会把「昨天的汇率」说成「刚刚取的汇率」，正是 spec 禁止的错觉。
     */
    private Instant parseUpdatedAt(JsonNode node) {
        if (!node.isNumber()) {
            throw fail("time_last_update_unix missing");
        }
        return Instant.ofEpochSecond(node.asLong());
    }

    private static BizException fail(String reason) {
        log.warn("exchange-rate read failed, reason={}", reason);
        return new BizException(TravelErrorEnum.TRAVEL_CURRENCY_UNAVAILABLE);
    }
}
