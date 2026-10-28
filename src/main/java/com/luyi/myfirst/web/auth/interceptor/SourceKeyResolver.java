package com.luyi.myfirst.web.auth.interceptor;

import jakarta.servlet.http.HttpServletRequest;

/**
 * 把一次请求翻译成限流用的来源键（spec「登录失败以来源限流代替账户锁定」）。
 *
 * 接口存在的意义是把「来源怎么认定」与「怎么计数」拆开：计数在 {@code LoginThrottle}，
 * 认定规则只在这里实现一处。若让调用方各自拼键，同一个请求在两个入口就会得到两个来源。
 *
 * 实现 MUST NOT 返回 null 或空串：取不到可信地址也要给出「未知来源」桶的键，
 * 否则限流会因为「没有来源」而静默放行。
 */
public interface SourceKeyResolver {

    String resolve(HttpServletRequest request);
}
