package com.luyi.myfirst.service.profile.model;

import java.util.List;

/**
 * 资料部分更新的入参。
 *
 * 四个字段全部可空：{@code null} 表示「未提交、保持原值」。空串 / 空集合表示「清空」。
 * 归一后的语义由 {@code ProfileRules} 裁定，这个 record 只承载值，不承载判定。
 */
public record ProfileUpdate(
        String displayName,
        String avatarUrl,
        String bio,
        List<String> interests) {
}
