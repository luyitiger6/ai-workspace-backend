package com.luyi.myfirst.service.post.interaction.model;

import com.luyi.myfirst.common.enums.VoteDirection;

/** 仅当前账户的有效选择，不包含总数或持久化实体。 */
public record PostInteractionState(VoteDirection voteDirection, boolean bookmarked) {
}
