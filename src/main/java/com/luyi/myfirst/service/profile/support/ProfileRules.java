package com.luyi.myfirst.service.profile.support;

import com.luyi.myfirst.common.exception.AuthErrorEnum;
import com.luyi.myfirst.common.exception.BizException;
import com.luyi.myfirst.service.profile.model.ProfileUpdate;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * 资料四项入参的归一与门槛（design D5，spec「资料字段口径」「兴趣标签取值边界」「头像地址不代抓取」）。
 *
 * 所有失败都归 VALIDATION_FAILED（400）：这些条件与调用方身份无关，不能借用 403/404 表达。
 * 长度单位是 code point（String#codePointCount），与 MySQL VARCHAR 按字符计的语义一致。
 *
 * 部分更新的语义在这里完成翻译：null 表示「未提交、保持原值」，空串 / 空集合表示「清空」，
 * 非空则按边界校验并归一。这里是写路径上唯一的校验点，service 与 web 层不重复判断。
 */
public final class ProfileRules {

    public static final int DISPLAY_NAME_MIN_CHARS = 1;
    public static final int DISPLAY_NAME_MAX_CHARS = 64;
    public static final int BIO_MAX_CHARS = 200;
    public static final int INTERESTS_MAX_COUNT = 5;
    public static final int INTEREST_MAX_CHARS = 16;

    private ProfileRules() {
    }

    /** 校验并归一：昵称 strip、标签去重、头像校验合法地址。失败抛 400，不改任何字段。 */
    public static ProfileUpdate normalize(ProfileUpdate raw) {
        String displayName = normalizeDisplayName(raw.displayName());
        String avatarUrl = normalizeAvatarUrl(raw.avatarUrl());
        String bio = normalizeBio(raw.bio());
        List<String> interests = normalizeInterests(raw.interests());
        return new ProfileUpdate(displayName, avatarUrl, bio, interests);
    }

    private static String normalizeDisplayName(String raw) {
        if (raw == null) {
            return null;
        }
        String stripped = raw.strip();
        int chars = stripped.codePointCount(0, stripped.length());
        if (chars < DISPLAY_NAME_MIN_CHARS || chars > DISPLAY_NAME_MAX_CHARS) {
            throw rejected();
        }
        return stripped;
    }

    private static String normalizeAvatarUrl(String raw) {
        if (raw == null) {
            return null;
        }
        if (raw.isBlank()) {
            return "";
        }
        String candidate = raw.strip();
        try {
            URI uri = new URI(candidate);
            String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
            if (!uri.isAbsolute() || uri.getHost() == null || !Set.of("http", "https").contains(scheme)) {
                throw rejected();
            }
            return candidate;
        } catch (URISyntaxException broken) {
            throw rejected();
        }
    }

    private static String normalizeBio(String raw) {
        if (raw == null) {
            return null;
        }
        int chars = raw.codePointCount(0, raw.length());
        if (chars > BIO_MAX_CHARS) {
            throw rejected();
        }
        return raw;
    }

    private static List<String> normalizeInterests(List<String> raw) {
        if (raw == null) {
            return null;
        }
        if (raw.isEmpty()) {
            return List.of();
        }
        // 先去重再判上限：重复取值不占额度，6 个重复值去重后是 1 个，不算超量。
        Set<String> unique = new LinkedHashSet<>();
        for (String each : raw) {
            String tag = each == null ? "" : each.strip();
            int chars = tag.codePointCount(0, tag.length());
            if (chars < 1 || chars > INTEREST_MAX_CHARS) {
                throw rejected();
            }
            if (!InterestCatalog.contains(tag)) {
                throw rejected();
            }
            unique.add(tag);
        }
        if (unique.size() > INTERESTS_MAX_COUNT) {
            throw rejected();
        }
        return List.copyOf(new ArrayList<>(unique));
    }

    private static BizException rejected() {
        return new BizException(AuthErrorEnum.VALIDATION_FAILED);
    }
}
