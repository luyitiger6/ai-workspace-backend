package com.luyi.myfirst.service.dm.model;

/** 发送一条消息的入参：只有正文，发送者身份一律取自令牌。 */
public record DmSendCommand(String content) {
}
