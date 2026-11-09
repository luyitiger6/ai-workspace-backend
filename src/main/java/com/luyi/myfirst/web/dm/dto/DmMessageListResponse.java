package com.luyi.myfirst.web.dm.dto;

import com.luyi.myfirst.common.web.ApiResponse;

import java.util.List;

/**
 * 消息分页响应：{@code nextCursor} 为 null 表示没有更多消息。
 *
 * 空数组 + null 游标是「取到最早一条后继续翻页」的正常结果，不是错误。
 */
public record DmMessageListResponse(
        List<DmMessageResponse> items,
        String nextCursor) implements ApiResponse {
}
