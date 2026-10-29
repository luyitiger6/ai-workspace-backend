package com.luyi.myfirst.web.post.interaction.dto;

/**
 * 投票请求体。
 *
 * {@code direction} 取值为 {@code up} 或 {@code down}；作者取自令牌。
 */
public record VoteCreateRequest(String direction) {
}
