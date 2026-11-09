package com.luyi.myfirst.service.dm.model;

import java.time.Instant;

/**
 * 一条私信消息的对外视图。
 *
 * 只有公开标识、发送者公开标识、正文与创建时间：内部 {@code id} 与
 * {@code conversationId} 不外泄（游标由服务端编码，不把自增主键交给前端）。
 */
public record DmMessageView(
        String publicId,
        String senderPublicId,
        String content,
        Instant createdAt) {
}
