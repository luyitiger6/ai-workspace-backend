package com.luyi.myfirst.web.dm.dto;

import com.luyi.myfirst.common.web.ApiResponse;

import java.time.Instant;

/**
 * 单条消息（发送响应、分页项、SSE 事件体三处复用）。
 *
 * 事件流与 REST 用同一个形状：前端对两个来源用同一段合并逻辑，
 * 按 {@code publicId} 去重即可，不必为推送单独维护一套字段映射。
 */
public record DmMessageResponse(
        String publicId,
        String senderPublicId,
        String content,
        Instant createdAt) implements ApiResponse {
}
