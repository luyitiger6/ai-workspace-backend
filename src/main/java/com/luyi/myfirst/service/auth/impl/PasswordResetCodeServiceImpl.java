package com.luyi.myfirst.service.auth.impl;

import com.luyi.myfirst.service.auth.PasswordResetCodeService;
import com.luyi.myfirst.service.auth.support.CodePurpose;
import com.luyi.myfirst.service.auth.support.EmailCodeFlow;
import org.springframework.stereotype.Service;

/**
 * 重置码：邮箱验证码链路在「密码重置」这个用途上的门面。
 *
 * 与 {@link RegisterCodeServiceImpl} 是同一个形状——一行逻辑也没有，全部委托给
 * {@link EmailCodeFlow}。差别只在传下去的用途常量：它决定码发给谁（有 active 账户的邮箱）、
 * 缓存键落在哪个段（{@code wc:code:reset:}）、摘要的用途前缀取什么。
 *
 * 两个门面而不是一个带参数的实现，是为了让调用方永远拿不到「传错用途」的机会：
 * 注册端点只能发注册码，重置端点只能发重置码。
 */
@Service
public class PasswordResetCodeServiceImpl implements PasswordResetCodeService {

    private final EmailCodeFlow flow;

    public PasswordResetCodeServiceImpl(EmailCodeFlow flow) {
        this.flow = flow;
    }

    /** 换发重置码：只发给「已有 active 账户」的邮箱，其余情形同形 202 但不投递。 */
    @Override
    public void issue(String email, String captchaId, String captchaAnswer) {
        flow.issue(email, CodePurpose.PASSWORD_RESET, captchaId, captchaAnswer);
    }

    /** 比对重置码：匹配即删除，不匹配记一次失败，失败达上限则连同码一起作废。 */
    @Override
    public void verify(String email, String code) {
        flow.verify(email, code, CodePurpose.PASSWORD_RESET);
    }

    /** 重置码的缓存键。测试要清理它，也用它断言「未注册/已注销邮箱不落码」。 */
    public String keyOf(String normalizedEmail) {
        return flow.keyOf(normalizedEmail, CodePurpose.PASSWORD_RESET);
    }

    /** 重置码失败计数的键。 */
    public String failKeyOf(String normalizedEmail) {
        return flow.failKeyOf(normalizedEmail, CodePurpose.PASSWORD_RESET);
    }

    /** 重发冷却键。与注册码共用同一个键，见 {@link EmailCodeFlow#cooldownKeyOf}。 */
    public String cooldownKeyOf(String normalizedEmail) {
        return flow.cooldownKeyOf(normalizedEmail);
    }
}
