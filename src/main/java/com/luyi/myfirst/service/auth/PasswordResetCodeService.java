package com.luyi.myfirst.service.auth;

/**
 * 重置码的换发与比对（spec「密码找回」的前半段）。
 *
 * 与 {@link RegisterCodeService} 是同一套机制的两个用途：图形码、冷却、日配额、
 * 6 位码生成、摘要存储、一次性与失败上限全部共用，区别只有两点——
 * 码只发给「已有 active 账户」的邮箱，且验证通过后由调用方去改密码而不是建账户。
 *
 * 这里仍然只声明「发码」与「验码」两件事，缓存键布局与失败计数留在实现里。
 */
public interface PasswordResetCodeService {

    /** 换发重置码：消费图形码 → 生成 6 位数字 → 摘要入缓存 → 投递。 */
    void issue(String email, String captchaId, String captchaAnswer);

    /** 比对重置码：匹配即删除，不匹配记一次失败，失败达上限则连同码一起作废。 */
    void verify(String email, String code);
}
