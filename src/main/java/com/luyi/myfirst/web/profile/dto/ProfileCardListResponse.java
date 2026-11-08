package com.luyi.myfirst.web.profile.dto;

import com.luyi.myfirst.common.web.ApiResponse;

import java.util.List;

/**
 * 名片批量结果。不存在或已注销的标识不出现（spec「未知标识被省略」）。
 */
public record ProfileCardListResponse(
        List<ProfileCardResponse> items) implements ApiResponse {
}
