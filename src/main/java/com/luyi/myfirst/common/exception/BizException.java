package com.luyi.myfirst.common.exception;

import org.springframework.http.HttpStatus;

/**
 * 业务拒绝：请求本身合法，但当前状态不允许它发生。
 *
 * 与 {@link IllegalAccountTransitionException} 的界线是「谁的错」：
 * 这里表示调用方撞上了可预期的拒绝；那里表示代码破坏了不变量，属于 bug。
 */
public class BizException extends RuntimeException {

    private final String errorCode;
    private final HttpStatus httpStatus;
    private final Long retryAfterSeconds;

    public BizException(BaseError error) {
        this(error, null);
    }

    public BizException(BaseError error, Long retryAfterSeconds) {
        super(error.getErrorMsg());
        this.errorCode = error.getErrorCode();
        this.httpStatus = error.getHttpStatus();
        this.retryAfterSeconds = retryAfterSeconds;
    }

    public String getErrorCode() {
        return errorCode;
    }

    public HttpStatus getHttpStatus() {
        return httpStatus;
    }

    /** 仅 429 需要：写进 Retry-After 头的秒数；其余错误为 null。 */
    public Long getRetryAfterSeconds() {
        return retryAfterSeconds;
    }
}
