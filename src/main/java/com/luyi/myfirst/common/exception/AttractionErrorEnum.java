package com.luyi.myfirst.common.exception;

import org.springframework.http.HttpStatus;

/**
 * 景点端点专有的错误码（design D5）。
 *
 * 只有两个。不复用 {@link PostErrorEnum}：错误码是模块所有物，跨模块借用会在
 * 双方归档之后形成一条没有 change 记录的隐式依赖。
 *
 * 反方向也不做：「未知 sortBy 回退 hot」而不是报错，因此不存在
 * {@code ATTRACTION_SORT_INVALID}；只有 {@code collection} 落在白名单外才 400，
 * 因为榜单键没有「合理的默认值可回退」——回落到某个榜单等于凭空篡改用户请求。
 *
 * 参数归一化的其它失败（limit 非数字等）由框架层按 {@code VALIDATION_FAILED} 处理，
 * 与 posts 域同一口径，这里不复制一份。
 */
public enum AttractionErrorEnum implements BaseError {

    ATTRACTION_NOT_FOUND("ATTRACTION_NOT_FOUND", "景点不存在", HttpStatus.NOT_FOUND),

    ATTRACTION_COLLECTION_INVALID("ATTRACTION_COLLECTION_INVALID", "榜单键不合法", HttpStatus.BAD_REQUEST);

    private final String errorCode;
    private final String errorMsg;
    private final HttpStatus httpStatus;

    AttractionErrorEnum(String errorCode, String errorMsg, HttpStatus httpStatus) {
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
