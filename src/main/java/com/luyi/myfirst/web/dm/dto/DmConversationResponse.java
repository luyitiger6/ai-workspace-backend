package com.luyi.myfirst.web.dm.dto;

import com.luyi.myfirst.common.web.ApiResponse;

import java.time.Instant;

/**
 * 会话摘要（发起、列表项、标记已读三处复用）。
 *
 * {@code lastMessagePreview} 与 {@code lastMessageAt} 在会话还没有消息时为 null：
 * 摘要只是最后一条消息的投影，没有消息就没有投影，不用空串假装有。
 */
public record DmConversationResponse(
        String publicId,
        DmPeerResponse peer,
        int messageCount,
        int unreadCount,
        String lastMessagePreview,
        Instant lastMessageAt,
        Instant createdAt) implements ApiResponse {
}
