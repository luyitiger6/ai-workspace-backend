package com.luyi.myfirst.service.auth;

/**
 * 密码重置（spec「密码找回」的后半段）。
 *
 * 与 {@link RegisterService} 是同一套两层码的两种去向：注册码验过之后建账户并当场下发令牌
 * （首次自动登录），重置码验过之后只换密码、不返回令牌。重置后不自动登录是有意的：那等于把
 * 一个「凭邮箱就能拿到的会话」交到刚刚才证明自己能收信的人手里，而重置的本意是把既有会话
 * 全部作废，不是再开一个。
 *
 * 验码通过即删键（一次性），所以本方法的失败分支只有两类：格式不合法、码不对。
 * 账户不存在与码不对合并成同一个出口——分开就是邮箱存在性预言机。
 */
public interface PasswordResetService {

    /**
     * 用重置码换掉密码。
     *
     * @param newPassword 明文新密码，长度按 UTF-8 字节计（8–72，与注册同一套规则）
     */
    void reset(String email, String code, String newPassword);
}
