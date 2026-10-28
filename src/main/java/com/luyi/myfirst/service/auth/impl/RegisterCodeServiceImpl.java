package com.luyi.myfirst.service.auth.impl;

import com.luyi.myfirst.service.auth.RegisterCodeService;
import com.luyi.myfirst.service.auth.support.CodePurpose;
import com.luyi.myfirst.service.auth.support.EmailCodeFlow;
import org.springframework.stereotype.Service;

/**
 * 注册码：邮箱验证码链路在「注册」这个用途上的门面。
 *
 * 一行逻辑也没有——图形码、冷却、日配额、生成、摘要、比对、失败计数全在
 * {@link EmailCodeFlow} 里，与重置码是同一份实现。这个类存在的理由只有一个：
 * 让调用方（注册端点与注册服务）不需要知道「用途」这个参数，也就没机会把它传错。
 *
 * 保留 {@code keyOf} / {@code failKeyOf} / {@code cooldownKeyOf} 三个公开访问器，
 * 是把缓存键布局留在实现类上（不进接口）：测试与排障要能精确清理自己造的键。
 */
@Service
public class RegisterCodeServiceImpl implements RegisterCodeService {

    private final EmailCodeFlow flow;

    public RegisterCodeServiceImpl(EmailCodeFlow flow) {
        this.flow = flow;
    }

    /** 换发注册码：只发给「还没有 active 账户」的邮箱，其余情形同形 202 但不投递。 */
    @Override
    public void issue(String email, String captchaId, String captchaAnswer) {
        flow.issue(email, CodePurpose.REGISTER, captchaId, captchaAnswer);
    }

    /** 比对注册码：匹配即删除，不匹配记一次失败，失败达上限则连同码一起作废。 */
    @Override
    public void verify(String email, String code) {
        flow.verify(email, code, CodePurpose.REGISTER);
    }

    /** 注册码的缓存键。测试要清理它，也用它断言「已注册邮箱不落码」。 */
    public String keyOf(String normalizedEmail) {
        return flow.keyOf(normalizedEmail, CodePurpose.REGISTER);
    }

    /** 注册码失败计数的键。 */
    public String failKeyOf(String normalizedEmail) {
        return flow.failKeyOf(normalizedEmail, CodePurpose.REGISTER);
    }

    /** 重发冷却键。与重置码共用同一个键，见 {@link EmailCodeFlow#cooldownKeyOf}。 */
    public String cooldownKeyOf(String normalizedEmail) {
        return flow.cooldownKeyOf(normalizedEmail);
    }
}
