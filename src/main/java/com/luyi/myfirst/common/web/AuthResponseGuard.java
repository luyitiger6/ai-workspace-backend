package com.luyi.myfirst.common.web;

import org.springframework.core.MethodParameter;
import org.springframework.http.MediaType;
import org.springframework.http.converter.HttpMessageConverter;
import org.springframework.http.server.ServerHttpRequest;
import org.springframework.http.server.ServerHttpResponse;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.servlet.mvc.method.annotation.ResponseBodyAdvice;

/**
 * 认证响应的唯一出站口（tasks 6.1，spec「所有响应 MUST 经统一响应包装出站，
 * 不得由各端点自行拼装响应体」；作用域按 design Q16 仅限 {@code /api/auth/**}）。
 *
 * 这是一道类型守卫而不是序列化改写：它不重塑响应体，只在响应体即将写出前检查
 * 「你是不是登记过的认证响应类型（{@link AuthResponseBody}）」。不是，就抛异常拦下，
 * 让那个忘记给新 DTO 打标记的端点在测试里立刻 500，而不是悄悄把任意形状写出去。
 *
 * 为什么用「拦下」而不是「编译期禁止」：Java 没法在编译期强制「某路径的控制器只能返回
 * 某类型」，但可以把约束移到唯一的出站口——所有 {@code /api/auth/**} 的响应体都必经
 * {@link ResponseBodyAdvice}，在这一个点按类型判定，等于把「N 个端点各自别返回敏感形状」
 * 收敛成「一个守卫只放行标记类型」。新增端点绕过它的唯一方式是不走 Spring MVC 出站，
 * 而那本身就脱离了整套拦截器与异常收口，是更显性的偏差。
 *
 * 空体（{@code ResponseEntity<Void>}、204/202）不在约束内：没有响应体就没有可泄漏的字段，
 * 直接放行。非 {@code /api/auth/**} 的路径原样返回——全站化是另一个变更（Q16）。
 */
@RestControllerAdvice
public class AuthResponseGuard implements ResponseBodyAdvice<Object> {

    /** 认证端点的统一路径前缀，与 {@code WebMvcConfig} 的拦截器注册口径一致。 */
    static final String AUTH_PATH_PREFIX = "/api/auth/";

    /**
     * 框架自带的响应写出者所在包前缀。未捕获异常会让容器把请求 ERROR 转发到
     * {@code BasicErrorController}（在 {@code org.springframework.boot} 下），由它写出一份 500 错误页
     * 属性 Map——那不是「认证端点自行拼装响应体」，拦下它只会把一个 500 变成另一个 500。
     */
    private static final String FRAMEWORK_PACKAGE_PREFIX = "org.springframework";

    @Override
    public boolean supports(MethodParameter returnType, Class<? extends HttpMessageConverter<?>> converterType) {
        // 一律进入 beforeBodyWrite，由它按路径判定：supports 阶段拿不到请求路径。
        return true;
    }

    @Override
    public Object beforeBodyWrite(Object body,
                                  MethodParameter returnType,
                                  MediaType selectedContentType,
                                  Class<? extends HttpMessageConverter<?>> selectedConverterType,
                                  ServerHttpRequest request,
                                  ServerHttpResponse response) {
        // 空体（ResponseEntity<Void>、204/202）没有可泄漏的字段，直接放行。
        if (body == null) {
            return null;
        }
        // 已登记的认证响应类型无条件放行：标记即「我属于统一出站白名单」。
        if (body instanceof AuthResponseBody) {
            return body;
        }
        // 未标记的响应体，只有落在 /api/auth/** 才归本守卫管（design Q16：作用域仅限认证端点）。
        String path = request.getURI().getPath();
        if (path == null || !path.startsWith(AUTH_PATH_PREFIX)) {
            return body;
        }
        // 容器错误页（BasicErrorController 等框架处理器写出的 5xx 兜底体）放行，理由见常量注释。
        if (returnType != null
                && returnType.getContainingClass().getPackageName().startsWith(FRAMEWORK_PACKAGE_PREFIX)) {
            return body;
        }
        // 走到这里说明有一个 /api/auth/** 端点试图写出未登记的响应体：拦下，让它 500。
        // 异常消息只进服务端日志（Spring 默认不把 message 写进 500 响应体），用于定位是哪个类型漏了标记。
        throw new IllegalStateException(
                "认证端点响应体未经统一包装（缺少 AuthResponseBody 标记）："
                        + body.getClass().getName() + " @ " + path);
    }
}
