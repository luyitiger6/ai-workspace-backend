package com.luyi.myfirst.web.profile.dto;

import com.luyi.myfirst.common.web.ApiResponse;

import java.util.List;

/**
 * 本人完整资料的响应体：昵称、头像、简介与兴趣标签四项（spec「资料字段口径」）。
 *
 * interests 是去重后的标签列表；不含 id / email / status / deletedAt 等内部字段。
 */
public record ProfileResponse(
        String publicId,
        String displayName,
        String avatarUrl,
        String bio,
        List<String> interests) implements ApiResponse {
}
