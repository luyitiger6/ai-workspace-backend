package com.luyi.myfirst.service.auth.model;

/**
 * 注册结果。
 *
 * 只带三个可回显的账户字段，加上一张令牌。令牌的用法被 {@link IssuedToken} 限制住了：
 * 它只能交给 {@link AuthCookie}，没有别的消费方式——注册响应体里检索不到它。
 */
public record Registration(String publicId, String email, String displayName, IssuedToken token) {
}
