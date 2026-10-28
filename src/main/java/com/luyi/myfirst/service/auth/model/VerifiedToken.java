package com.luyi.myfirst.service.auth.model;

import com.luyi.myfirst.dao.auth.entity.Account;

/**
 * 一次通过的认证结果：谁、凭哪张令牌。
 *
 * jti 必须与账户一起传出来——登出要吊销「当前这一张」而不是该账户的全部令牌，
 * 只传 Account 的话，控制器只能重新解析一遍 cookie 才知道吊销谁。
 */
public record VerifiedToken(Account account, String jti) {
}
