package com.luyi.myfirst.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

/**
 * 两个外部数据源的 HTTP 客户端装配。
 *
 * 单独成配置类而不是在 provider 内部 new：客户端是「带超时的连接」这件事本身，
 * 把它作为构造入参，provider 就能在单测里换成一个绑定 {@code MockRestServiceServer}
 * 的实例，不必真的发请求、也不必等超时。
 *
 * 两个 bean 必须分开命名而不是靠参数名猜：它们只是超时相同，基地址与将来的凭据都不同，
 * 合成一个会让「给天气源加 key」变成给汇率源也加一遍。
 *
 * 超时取自 {@link TravelProperties}，与配置同源，改一处即两边生效。
 */
@Configuration
public class TravelClientConfig {

    @Bean
    RestClient weatherRestClient(TravelProperties properties) {
        return build(properties, properties.weatherBaseUrl());
    }

    @Bean
    RestClient currencyRestClient(TravelProperties properties) {
        return build(properties, properties.currencyBaseUrl());
    }

    private static RestClient build(TravelProperties properties, String baseUrl) {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout((int) properties.connectTimeout().toMillis());
        factory.setReadTimeout((int) properties.readTimeout().toMillis());
        return RestClient.builder()
                .baseUrl(baseUrl)
                .requestFactory(factory)
                .build();
    }
}
