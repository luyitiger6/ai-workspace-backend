package com.luyi.myfirst.web.dm.dto;

/**
 * 发起会话的请求体：只接受对方的公开标识。
 *
 * 不接受内部 id、邮箱或昵称——公开标识是唯一对外可用的账户句柄，
 * 其余标识一旦被接受，就等于多开了几条可以枚举账户的入口。
 */
public record CreateConversationRequest(String peerPublicId) {
}
