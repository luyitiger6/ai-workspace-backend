package com.luyi.myfirst.service.dm.model;

/**
 * 发起会话的结果：会话本身 + 是否本次新建。
 *
 * 控制器靠 {@code created} 决定状态码：首次创建 201，重复发起返回既有会话 200
 * （spec「首次发起创建会话」与「重复发起返回同一会话」）。
 * 不把状态码判断塞进服务层——服务层只说「发生了什么」，HTTP 语义留在 web 层。
 */
public record DmOpenResult(DmConversationSummary conversation, boolean created) {
}
