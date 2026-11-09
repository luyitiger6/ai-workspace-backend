package com.luyi.myfirst.web.dm.dto;

import com.luyi.myfirst.common.web.ApiResponse;

/**
 * 会话里的对方名片（design D6）。
 *
 * 三个字段与 {@code ProfileCardResponse} 的含义一致，不新造概念；
 * 后端一次装配，前端不再为 50 个会话拼一次名片查询。
 */
public record DmPeerResponse(
        String publicId,
        String displayName,
        String avatarUrl) implements ApiResponse {
}
