package com.luyi.myfirst.common.exception;

import org.springframework.http.HttpStatus;

/**
 * 旅行工具（天气 / 汇率）专有的错误码。
 *
 * 不复用 {@link AttractionErrorEnum} 或其它既有枚举：错误码是模块所有物，
 * 跨模块借用会在双方归档之后形成一条没有 change 记录的隐式依赖。
 *
 * 上游失败一律 502 而不是 503：503 的含义是「本服务暂时不可用」，而这里本服务是健康的，
 * 坏的是被代理的外部数据源。前端只需要区分「拿到了」与「没拿到」，不必区分是哪一种上游故障。
 *
 * 不提供「数据源返回内容不可解析」的独立错误码：对调用方而言，超时、连不上、
 * 返回垃圾响应是同一件事——这次没拿到数据。运维侧靠 WARN 日志里的 cause 区分。
 */
public enum TravelErrorEnum implements BaseError {

    TRAVEL_CITY_INVALID("TRAVEL_CITY_INVALID", "城市不在支持范围内", HttpStatus.BAD_REQUEST),

    TRAVEL_WEATHER_UNAVAILABLE("TRAVEL_WEATHER_UNAVAILABLE", "天气数据源暂时不可用", HttpStatus.BAD_GATEWAY),

    TRAVEL_CURRENCY_UNAVAILABLE("TRAVEL_CURRENCY_UNAVAILABLE", "汇率数据源暂时不可用", HttpStatus.BAD_GATEWAY);

    private final String errorCode;
    private final String errorMsg;
    private final HttpStatus httpStatus;

    TravelErrorEnum(String errorCode, String errorMsg, HttpStatus httpStatus) {
        this.errorCode = errorCode;
        this.errorMsg = errorMsg;
        this.httpStatus = httpStatus;
    }

    @Override
    public String getErrorCode() {
        return errorCode;
    }

    @Override
    public String getErrorMsg() {
        return errorMsg;
    }

    @Override
    public HttpStatus getHttpStatus() {
        return httpStatus;
    }
}
