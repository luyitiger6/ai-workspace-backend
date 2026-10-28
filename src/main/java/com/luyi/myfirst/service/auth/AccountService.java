package com.luyi.myfirst.service.auth;

import com.luyi.myfirst.service.auth.model.VerifiedToken;

import java.time.Instant;

/**
 * 账户主体操作。本变更只有注销一条，因此不给它铺一套通用 CRUD 的壳。
 *
 * 入参是 {@link VerifiedToken} 而不是 cookie 或令牌串：谁来调用、凭哪张令牌，
 * 已经在拦截器里定过了，服务层不再自己解析一遍请求。
 */
public interface AccountService {

    /**
     * 本人注销：账户转 {@code deleted} 终态，同时让此前签发的全部令牌失效。
     *
     * 令牌是按 token_version 一次性作废的，不是逐条吊销——后者会漏掉
     * 「旧令牌还没登记完成就被注销」这类边角，版本号不会。
     */
    void delete(VerifiedToken verified, Instant now);
}
