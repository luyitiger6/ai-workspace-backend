package com.luyi.myfirst.common.exception;

import org.springframework.http.HttpStatus;

/**
 * 错误码契约。
 *
 * HTTP 状态码放在错误码里，而不是在处理器里按码段猜：spec 已经把
 * 「email_unverified 登录 403、限流 429」这类映射钉死，散在 if 里迟早对不上。
 */
public interface BaseError {

    String getErrorCode();

    String getErrorMsg();

    HttpStatus getHttpStatus();
}
