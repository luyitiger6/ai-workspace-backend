package com.luyi.myfirst.common.web;

import com.luyi.myfirst.common.exception.AuthErrorEnum;
import com.luyi.myfirst.common.exception.BizException;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.multipart.MaxUploadSizeExceededException;

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

    /**
     * 容器层的 multipart 上限先于业务层触发，抛的是 Spring 自己的异常而不是业务拒绝。
     * 不登记的话，「文件太大」会变成一个容器默认的 500，与其它上传拒绝（统一 400）分家。
     *
     * 这不是通用兜底：只翻译这一个已知会绕过业务层的容器层拒绝。
     */
    @ExceptionHandler(MaxUploadSizeExceededException.class)
    public ResponseEntity<ErrorResponse> handleUploadTooLarge() {
        return ResponseEntity.status(AuthErrorEnum.VALIDATION_FAILED.getHttpStatus())
                .body(new ErrorResponse(
                        AuthErrorEnum.VALIDATION_FAILED.getErrorCode(),
                        AuthErrorEnum.VALIDATION_FAILED.getErrorMsg()));
    }
}
