package com.luyi.myfirst.web.profile.dto;

import com.luyi.myfirst.common.web.ApiResponse;

import java.util.List;

/**
 * 兴趣标签清单。清单是资料校验与界面展示的唯一真源（spec「兴趣标签清单可匿名读取」）。
 */
public record InterestListResponse(
        List<String> items) implements ApiResponse {
}
