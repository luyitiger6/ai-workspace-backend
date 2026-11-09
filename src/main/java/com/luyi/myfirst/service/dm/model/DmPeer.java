package com.luyi.myfirst.service.dm.model;

/**
 * 会话里的对方名片（design D6）。
 *
 * 字段与 {@code wanderchain-profile} 的公开名片一致，不新造概念：
 * 只有对外标识、昵称与头像地址三项，不含简介、兴趣、邮箱与状态。
 *
 * 对方注销后会话仍可读（spec 不要求清理已删除账号的会话），
 * 此时昵称与头像可能为 {@code null}，由前端做兜底展示。
 */
public record DmPeer(
        String publicId,
        String displayName,
        String avatarUrl) {
}
