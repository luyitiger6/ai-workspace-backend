package com.luyi.myfirst.service.auth;

/**
 * 注册码的换发与比对（spec「注册验证码链路」的两步）。
 *
 * 只声明「发码」与「验码」两件事。缓存键的内部构成、失败计数、冷却键都留在实现里：
 * 它们是这个能力的实现细节，写进接口就等于让调用方依赖 Redis 的键布局。
 */
public interface RegisterCodeService {

    /** 换发注册码：消费图形码 → 生成 6 位数字 → 摘要入缓存 → 投递。 */
    void issue(String email, String captchaId, String captchaAnswer);

    /** 比对注册码：匹配即删除，不匹配记一次失败，失败达上限则连同码一起作废。 */
    void verify(String email, String code);
}
