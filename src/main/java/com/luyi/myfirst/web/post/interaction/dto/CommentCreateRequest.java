package com.luyi.myfirst.web.post.interaction.dto;

/**
 * 发表评论请求体。
 *
 * {@code parentPublicId} 为 null 表示顶级评论；回复评论时携带被回复评论的对外标识。
 * 作者取自令牌，请求体中不存在作者字段。
 */
public record CommentCreateRequest(String content, String parentPublicId) {
}
