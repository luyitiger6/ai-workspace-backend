package com.luyi.myfirst.common.exception;

import org.springframework.http.HttpStatus;

/**
 * 资料域专有的错误码。
 *
 * 只有一个：不存在、已注销与形状不合共用 {@code PROFILE_NOT_FOUND}，不给调用方留
 * 「为什么没找到」的差异信道（spec「公开用户资料的未找到口径」）。其余通用码
 * （{@code UNAUTHENTICATED} / {@code VALIDATION_FAILED} / {@code CROSS_SITE_REJECTED}）
 * 仍直接用 {@link AuthErrorEnum}，不在这里再复制一份。
 */
public enum ProfileErrorEnum implements BaseError {

    PROFILE_NOT_FOUND("PROFILE_NOT_FOUND", "用户不存在", HttpStatus.NOT_FOUND);

    private final String errorCode;
    private final String errorMsg;
    private final HttpStatus httpStatus;

    ProfileErrorEnum(String errorCode, String errorMsg, HttpStatus httpStatus) {
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
