package com.luyi.myfirst.web.dm.dto;

import com.luyi.myfirst.common.web.ApiResponse;

import java.util.List;

/** 会话列表响应：没有会话时 {@code items} 为空数组，不是错误。 */
public record DmConversationListResponse(List<DmConversationResponse> items) implements ApiResponse {
}
