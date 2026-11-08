package com.luyi.myfirst.web.profile.dto;

import com.luyi.myfirst.common.web.ApiResponse;

/**
 * 公开名片：只含对外标识、昵称与头像三个字段（spec「游客批量读名片只含三个字段」）。
 *
 * 不含简介、兴趣标签、邮箱与状态——名片要能被游客读到，多一个字段就是把可枚举的信息面扩大一寸。
 */
public record ProfileCardResponse(
        String publicId,
        String displayName,
        String avatarUrl) implements ApiResponse {
}
