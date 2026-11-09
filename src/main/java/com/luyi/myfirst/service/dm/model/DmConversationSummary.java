package com.luyi.myfirst.service.dm.model;

import java.time.Instant;

/**
 * 会话摘要：会话列表的一项，也是发起会话与标记已读的返回形状。
 *
 * {@code unreadCount} 是请求者一侧的计数，直接取会话表的计数列（design D2），
 * 不在读取路径上聚合消息表。
 *
 * {@code lastMessagePreview} 与 {@code lastMessageAt} 在没有消息时为 {@code null}
 * ——不用空串表达「还没有消息」，否则前端要同时判断空串与 null 两种「空」。
 * 摘要超过 50 个字符时已按码点截断（spec「每项含对方名片与未读数」）。
 */
public record DmConversationSummary(
        String publicId,
        DmPeer peer,
        int messageCount,
        int unreadCount,
        String lastMessagePreview,
        Instant lastMessageAt,
        Instant createdAt) {
}
