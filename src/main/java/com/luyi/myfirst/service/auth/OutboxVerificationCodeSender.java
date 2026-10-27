package com.luyi.myfirst.service.auth;

import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.List;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;

/**
 * 模拟发码程序：明文码写进进程内 outbox，不发真实邮件、不依赖网络（D8）。
 *
 * 三条护栏里这条最容易做错——`messages()` 只能给测试与开发期工具用，
 * MUST NOT 有任何 HTTP 端点把它读出来。那会直接违反「响应禁含验证码」。
 *
 * 进程内而非投递记录表：本变更只要求链路可跑通，重启即清空反而是对的——
 * 真实的投递记录该由真实通道产生，模拟件不该留痕。
 */
@Component
public class OutboxVerificationCodeSender implements VerificationCodeSender {

    private final Queue<Message> messages = new ConcurrentLinkedQueue<>();

    @Override
    public void send(String email, String code, CodePurpose purpose) {
        messages.add(new Message(email, code, purpose, Instant.now()));
    }

    /** 已投递的消息，按投递顺序。 */
    public List<Message> messages() {
        return List.copyOf(messages);
    }

    public void clear() {
        messages.clear();
    }

    public record Message(String email, String code, CodePurpose purpose, Instant sentAt) {
    }
}
