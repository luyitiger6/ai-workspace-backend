package com.luyi.myfirst.common.web;

/**
 * 认证端点的统一错误响应。
 *
 * 只装错误码与提示：状态码在 HTTP 层，Retry-After 在响应头。
 * 把它们复制进响应体，就等于同一事实有了两份来源，迟早不一致。
 */
public record ErrorResponse(String code, String message) implements AuthResponseBody {
}
