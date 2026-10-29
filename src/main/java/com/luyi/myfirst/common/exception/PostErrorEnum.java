package com.luyi.myfirst.common.exception;

import org.springframework.http.HttpStatus;

/**
 * 帖子端点专有的错误码（design D7）。
 *
 * 只有两个：其余三个（{@code UNAUTHENTICATED} / {@code CROSS_SITE_REJECTED} /
 * {@code VALIDATION_FAILED}）直接用 {@link AuthErrorEnum} 的常量，不在这里复制一份——
 * 同一错误码存在两个真源迟早会状态码漂移。反方向也不成立：把这两个码塞进
 * {@link AuthErrorEnum} 等于给已归档的认证契约扩固定集合。
 *
 * 乐观锁冲突（{@code ObjectOptimisticLockingFailureException}）**不**在这里新增第三个码：
 * 它落到框架默认 500，见 design D9。
 */
public enum PostErrorEnum implements BaseError {

    POST_FORBIDDEN("POST_FORBIDDEN", "无权操作该帖子", HttpStatus.FORBIDDEN),

    POST_NOT_FOUND("POST_NOT_FOUND", "帖子不存在", HttpStatus.NOT_FOUND);

    private final String errorCode;
    private final String errorMsg;
    private final HttpStatus httpStatus;

    PostErrorEnum(String errorCode, String errorMsg, HttpStatus httpStatus) {
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
