package com.luyi.myfirst.service.auth.support;

/**
 * 发码端口（D8）。
 *
 * 签名只描述「给谁发、发什么、为什么发」，不暴露通道细节：接真实邮件服务时只换实现，
 * 领域层与接入层零改动。
 */
public interface VerificationCodeSender {

    void send(String email, String code, CodePurpose purpose);
}
