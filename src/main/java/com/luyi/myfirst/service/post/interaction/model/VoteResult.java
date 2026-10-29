package com.luyi.myfirst.service.post.interaction.model;

/**
 * 投票结果：视图 + 是否为新建（控制器据此返回 201/200）。
 */
public record VoteResult(boolean created, VoteView view) {
}
