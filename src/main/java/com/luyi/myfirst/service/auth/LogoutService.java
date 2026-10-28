package com.luyi.myfirst.service.auth;

import com.luyi.myfirst.service.auth.model.VerifiedToken;

/**
 * 登出：只吊销当前这一张令牌。
 *
 * 重复登出不报错——用户连点两次、前端失败重试都不该变成 401 或 500。
 */
public interface LogoutService {

    void logout(VerifiedToken verified);
}
