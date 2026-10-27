package com.luyi.myfirst.common.exception;

import org.springframework.http.HttpStatus;

/**
 * 认证端点对外暴露的全部错误码。
 *
 * 取值与 spec「统一错误结构」一条逐字一致。该条同时写明「新增错误码属于契约变更」，
 * 所以这里只有七个——不能因为实现方便顺手加第八个。
 */
public enum AuthErrorEnum implements BaseError {

    UNAUTHENTICATED("UNAUTHENTICATED", "未认证", HttpStatus.UNAUTHORIZED),

    EMAIL_UNVERIFIED("EMAIL_UNVERIFIED", "邮箱尚未验证", HttpStatus.FORBIDDEN),

    CROSS_SITE_REJECTED("CROSS_SITE_REJECTED", "请求来源不可信", HttpStatus.FORBIDDEN),

    INVALID_CODE("INVALID_CODE", "验证码错误或已过期", HttpStatus.BAD_REQUEST),

    CAPTCHA_INVALID("CAPTCHA_INVALID", "图形验证码错误或已过期", HttpStatus.BAD_REQUEST),

    RATE_LIMITED("RATE_LIMITED", "请求过于频繁，请稍后再试", HttpStatus.TOO_MANY_REQUESTS),

    VALIDATION_FAILED("VALIDATION_FAILED", "参数不合法", HttpStatus.BAD_REQUEST);

    private final String errorCode;
    private final String errorMsg;
    private final HttpStatus httpStatus;

    AuthErrorEnum(String errorCode, String errorMsg, HttpStatus httpStatus) {
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
