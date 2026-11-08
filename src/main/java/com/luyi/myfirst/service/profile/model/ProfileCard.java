package com.luyi.myfirst.service.profile.model;

/**
 * 公开名片：只含对外标识、昵称与头像地址三个字段（spec「游客批量读名片只含三个字段」）。
 *
 * 它是白名单刻意收窄的形状：不含简介、兴趣标签、邮箱与状态——名片要能被游客读到，
 * 多一个字段就是把可枚举的信息面扩大一寸。
 */
public record ProfileCard(
        String publicId,
        String displayName,
        String avatarUrl) {
}
