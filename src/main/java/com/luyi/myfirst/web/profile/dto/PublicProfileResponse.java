package com.luyi.myfirst.web.profile.dto;

import com.luyi.myfirst.common.web.ApiResponse;

import java.util.List;

/**
 * 公开用户页的资料响应：对外标识、昵称、头像、简介与兴趣标签五项。
 *
 * 与本人读的 {@link ProfileResponse} 字段此刻相同，但入口语义不同：本人读将来加私有字段
 * （邮箱、未读通知数）时不应自动公开。独立 record 让「公开读能返回什么」只有一个改动点
 * （design D3）。
 */
public record PublicProfileResponse(
        String publicId,
        String displayName,
        String avatarUrl,
        String bio,
        List<String> interests) implements ApiResponse {
}
