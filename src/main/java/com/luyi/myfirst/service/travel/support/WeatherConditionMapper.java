package com.luyi.myfirst.service.travel.support;

/**
 * WMO 天气现象代码到英文文案的映射（design D6）。
 *
 * 放在服务端而不是前端：码表是数据源的语言，翻译规则跟着数据源走。将来换成
 * OpenWeatherMap（它对每个城市返回自己的 {@code weather[].description}）时，
 * 只要新实现把文案直接塞进 {@code WeatherReading.condition}，前端一行都不用改。
 *
 * 未知码一律回落 {@code Unknown} 而不是抛异常：数据源将来新增码值时，
 * 结果是「文案不够精确」，不该升级成「整个查询失败」。
 */
public final class WeatherConditionMapper {

    private WeatherConditionMapper() {
    }

    /**
     * 把 WMO 代码翻成一句可展示的英文。
     *
     * @param wmoCode 数据源给的天气现象代码，可能为 null（响应缺字段）
     * @return 永不为 null 的英文文案
     */
    public static String describe(Integer wmoCode) {
        if (wmoCode == null) {
            return "Unknown";
        }
        return switch (wmoCode) {
            case 0 -> "Clear";
            case 1 -> "Mainly clear";
            case 2 -> "Partly cloudy";
            case 3 -> "Overcast";
            case 45 -> "Fog";
            case 48 -> "Depositing rime fog";
            case 51 -> "Light drizzle";
            case 53 -> "Moderate drizzle";
            case 55 -> "Dense drizzle";
            case 56, 57 -> "Freezing drizzle";
            case 61 -> "Slight rain";
            case 63 -> "Moderate rain";
            case 65 -> "Heavy rain";
            case 66, 67 -> "Freezing rain";
            case 71 -> "Slight snowfall";
            case 73 -> "Moderate snowfall";
            case 75 -> "Heavy snowfall";
            case 77 -> "Snow grains";
            case 80 -> "Slight rain showers";
            case 81 -> "Moderate rain showers";
            case 82 -> "Violent rain showers";
            case 85 -> "Slight snow showers";
            case 86 -> "Heavy snow showers";
            case 95 -> "Thunderstorm";
            case 96, 99 -> "Thunderstorm with hail";
            default -> "Unknown";
        };
    }
}
