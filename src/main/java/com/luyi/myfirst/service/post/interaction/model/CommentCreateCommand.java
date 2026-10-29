package com.luyi.myfirst.service.post.interaction.model;

/**
 * 创建评论的命令：内容与被回复评论的对外标识（可选）。
 */
public record CommentCreateCommand(String content, String parentPublicId) {
}
