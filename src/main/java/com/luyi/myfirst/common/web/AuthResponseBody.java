package com.luyi.myfirst.common.web;

/**
 * 认证端点响应体的统一标记接口（tasks 6.1，spec「所有响应 MUST 经统一响应包装出站」）。
 *
 * 它是 {@link ApiResponse} 在认证域的子接口，两层含义不同：
 * 父接口说「这是对外的响应体」，本接口说「这是认证端点的响应体，归 {@link AuthResponseGuard} 管」。
 *
 * 它是一个空接口，作用不在方法而在类型：凡是会从 {@code /api/auth/**} 写出去的响应体，
 * 都必须显式声明实现它。{@link AuthResponseGuard} 在出站口按类型拦截——没实现这个标记的
 * 响应体一律被拦下，端点因此无法「自行拼装响应体」绕开包装。
 *
 * 用标记接口而不是基类：响应体都是 record，Java 的 record 不能继承类，但可以 implements。
 * 让每个 DTO 在声明处多写一个 {@code implements AuthResponseBody}，就是把「我属于认证出站
 * 白名单」这件事写在类型上，而不是靠某个人记得去守卫里登记一行。
 */
public interface AuthResponseBody extends ApiResponse {
}
