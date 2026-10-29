package com.luyi.myfirst.common.web;

/**
 * 响应体的统一基类型（design D2）。
 *
 * 它是一个空接口，作用不在方法而在类型：凡是会写到 {@code /api/**} 的响应体，
 * 都在声明处表明「我属于对外响应白名单」。{@link AuthResponseBody} 是它在认证域的
 * 子接口，帖子域的 {@code PostResponse} / {@code PostSummaryResponse} 直接实现它。
 *
 * 用接口而不是基类：响应体全是 record，Java 的 record 不能 extends 类，但可以 implements。
 *
 * 必须说清的边界：这里只有**类型归属**，没有运行时守卫。{@link AuthResponseGuard}
 * 的作用域仍是 {@code /api/auth/**}，帖子响应它原样放行——字段白名单由测试断言兜住，
 * 不是由这个接口兜住（design Risks 第 2 条）。
 */
public interface ApiResponse {
}
