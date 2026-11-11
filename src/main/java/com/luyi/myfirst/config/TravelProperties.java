package com.luyi.myfirst.config;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import java.time.Duration;
import java.util.Map;

/**
 * 天气与汇率快速查询的可调参数（application.yml 的 wanderchain.travel）。
 *
 * 与 {@link AuthProperties} 同样刻意不给代码内默认值：缺一项就绑定失败、启动即失败，
 * 避免「改了配置却因代码里有兜底而没生效」。
 *
 * 两个外部数据源当前都不需要凭据：天气走 Open-Meteo，汇率走 ExchangeRate-API 的开放端点，
 * 因此本文件不承载任何 key。将来接入需要 key 的数据源时，凭据按既有口径从环境变量读，
 * 不写回本文件。
 *
 * cities 是「城市 slug 到坐标」的映射。天气按经纬度而不是城市名查询：城市名存在歧义
 * （境外同名地点不止一个），带撇号的城市名还要处理编码，坐标没有这些问题。
 *
 * @param weatherBaseUrl   天气数据源根地址，不含路径
 * @param currencyBaseUrl  汇率数据源根地址，不含路径
 * @param connectTimeout   出站建连超时，耗尽即失败，不挂住请求
 * @param readTimeout      出站读取超时
 * @param weatherCacheTtl  天气缓存有效期，即结果可见的最大陈旧窗口
 * @param currencyCacheTtl 汇率缓存有效期，与数据源每日更新一次的频率对齐
 * @param cities           城市的英文展示名与坐标
 */
@Validated
@ConfigurationProperties(prefix = "wanderchain.travel")
public record TravelProperties(
        @NotBlank String weatherBaseUrl,
        @NotBlank String currencyBaseUrl,
        @NotNull Duration connectTimeout,
        @NotNull Duration readTimeout,
        @NotNull Duration weatherCacheTtl,
        @NotNull Duration currencyCacheTtl,
        @NotEmpty Map<String, CityCoordinate> cities
) {

    /**
     * 单个城市的展示名与坐标。
     *
     * nameEn 由配置提供而不是由调用方从 slug 推导，避免「slug 转展示名」的规则散落在多处。
     */
    public record CityCoordinate(
            @NotBlank String nameEn,
            @NotNull Double latitude,
            @NotNull Double longitude
    ) {
    }
}
