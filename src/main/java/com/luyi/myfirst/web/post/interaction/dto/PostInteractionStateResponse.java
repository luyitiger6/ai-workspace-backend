package com.luyi.myfirst.web.post.interaction.dto;

import com.luyi.myfirst.common.web.ApiResponse;

/** 本人有效互动状态的独立两字段白名单，未投票时方向为 null。 */
public record PostInteractionStateResponse(String voteDirection, boolean bookmarked) implements ApiResponse {
}
