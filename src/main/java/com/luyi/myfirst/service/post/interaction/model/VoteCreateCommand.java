package com.luyi.myfirst.service.post.interaction.model;

import com.luyi.myfirst.common.enums.VoteDirection;

/**
 * 投票的命令：方向。
 */
public record VoteCreateCommand(VoteDirection direction) {
}
