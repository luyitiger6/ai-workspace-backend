package com.luyi.myfirst.common.exception;

import org.springframework.http.HttpStatus;

/**
 * 私信端点专有的错误码（design D8）。
 *
 * 只有三个：参数不合法（{@code VALIDATION_FAILED}）与未认证（{@code UNAUTHENTICATED}）
 * 直接用 {@link AuthErrorEnum} 的常量，不在这里复制——同一错误码存在两个真源迟早会状态码漂移。
 * 反方向也不成立：把这三个码塞进 {@link AuthErrorEnum} 等于给已归档的认证契约扩固定集合。
 *
 * 「会话不存在」与「请求者不是参与者」共用 {@link #DM_CONVERSATION_NOT_FOUND}：
 * spec 要求非参与者得到 404 且不暴露会话是否存在，拆成两个码反而把这个信息泄漏回去。
 */
public enum DmErrorEnum implements BaseError {

    DM_SELF_CONVERSATION("DM_SELF_CONVERSATION", "不能向自己发起私信", HttpStatus.BAD_REQUEST),

    DM_PEER_NOT_FOUND("DM_PEER_NOT_FOUND", "对方账户不存在", HttpStatus.NOT_FOUND),

    DM_CONVERSATION_NOT_FOUND("DM_CONVERSATION_NOT_FOUND", "会话不存在", HttpStatus.NOT_FOUND);

    private final String errorCode;
    private final String errorMsg;
    private final HttpStatus httpStatus;

    DmErrorEnum(String errorCode, String errorMsg, HttpStatus httpStatus) {
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
