package com.luyi.myfirst.web.profile.dto;

import java.util.List;

/**
 * 资料部分更新请求体（design D3）。
 *
 * 四个字段全部可空：{@code null} 表示「未提交、保持原值」，空串 / 空数组表示「清空」。
 * 缺字段与字段为 {@code null} 是同一件事。合法性由 service/profile/support 唯一裁定。
 */
public record UpdateProfileRequest(
        String displayName,
        String avatarUrl,
        String bio,
        List<String> interests) {
}
