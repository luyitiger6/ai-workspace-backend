package com.luyi.myfirst.common.web;

import com.luyi.myfirst.common.exception.BizException;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * 把 BizException 翻译成 HTTP 响应。
 *
 * 这里只处理显式登记的业务拒绝。其它异常不兜底改写——让 Spring 的默认 500
 * 暴露出来，比替它编一个看起来正常的错误码更容易在联调时被发现。
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler(BizException.class)
    public ResponseEntity<ErrorResponse> handleBusiness(BizException exception) {
        ResponseEntity.BodyBuilder response = ResponseEntity.status(exception.getHttpStatus());
        if (exception.getRetryAfterSeconds() != null) {
            response.header("Retry-After", String.valueOf(exception.getRetryAfterSeconds()));
        }
        return response.body(new ErrorResponse(exception.getErrorCode(), exception.getMessage()));
    }
}
