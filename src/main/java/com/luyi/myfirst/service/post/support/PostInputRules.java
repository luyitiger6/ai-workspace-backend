package com.luyi.myfirst.service.post.support;

import com.luyi.myfirst.common.enums.PostStatus;
import com.luyi.myfirst.common.exception.AuthErrorEnum;
import com.luyi.myfirst.common.exception.BizException;
import com.luyi.myfirst.service.post.model.PostCommand;

import java.net.URI;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * 帖子入参的规范化与门槛（design D10，spec「帖子入参校验」）。
 *
 * 所有失败都归 {@code VALIDATION_FAILED}（400）：这些条件与调用方身份无关，
 * 不能借用 403/404 表达，否则「参数不对」和「无权操作」在前端会变成同一个处理分支。
 *
 * 长度单位是**字符**（{@link String#codePointCount}），与 MySQL {@code VARCHAR(120)}
 * 按字符计的语义一致；只有正文字节数按 UTF-8 字节计，因为 spec 明写 65535 字节。
 *
 * 这里是写路径上唯一的校验点：服务层与 web 层都不再重复判断，
 * 否则新增一条入口就会漏掉一套规则。
 */
public final class PostInputRules {

    public static final int TITLE_MAX_CHARS = 120;
    public static final int CONTENT_MAX_BYTES = 65535;
    public static final int TAGS_MAX_COUNT = 10;
    public static final int TAG_MAX_CHARS = 32;

    private PostInputRules() {
    }

    /**
     * 校验并归一：标题与标签 trim、标签按键值去重、状态缺省为草稿。
     *
     * 归一后的值才是真正落库的值——「保存的是去重后的集合，读取详情时每个标签只出现一次」
     * 这条 Scenario 靠的就是这里返回的新列表，而不是调用方手里的原始列表。
     */
    public static PostCommand normalize(PostCommand raw) {
        String title = requireTitle(raw.title());
        String content = requireContent(raw.content());
        String coverImage = normalizeCoverImage(raw.coverImage());
        List<String> tags = normalizeTags(raw.tags());
        PostStatus status = raw.status() == null ? PostStatus.DRAFT : raw.status();
        return new PostCommand(title, content, coverImage, tags, status);
    }

    private static String requireTitle(String raw) {
        String title = raw == null ? "" : raw.strip();
        int chars = title.codePointCount(0, title.length());
        if (chars < 1 || chars > TITLE_MAX_CHARS) {
            throw rejected();
        }
        return title;
    }

    private static String requireContent(String raw) {
        if (raw == null || raw.isBlank()) {
            throw rejected();
        }
        if (raw.getBytes(StandardCharsets.UTF_8).length > CONTENT_MAX_BYTES) {
            throw rejected();
        }
        return raw;
    }

    private static String normalizeCoverImage(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
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

    private static List<String> normalizeTags(List<String> raw) {
        if (raw == null || raw.isEmpty()) {
            return List.of();
        }
        // 先按提交条目数判上限，再去重：11 个取值里即使有重复也说明建模错了，
        // 不能靠去重把超量输入「修」成合法。
        if (raw.size() > TAGS_MAX_COUNT) {
            throw rejected();
        }
        Set<String> unique = new LinkedHashSet<>();
        for (String each : raw) {
            String tag = each == null ? "" : each.strip();
            int chars = tag.codePointCount(0, tag.length());
            if (chars < 1 || chars > TAG_MAX_CHARS) {
                throw rejected();
            }
            unique.add(tag);
        }
        return List.copyOf(new ArrayList<>(unique));
    }

    private static BizException rejected() {
        return new BizException(AuthErrorEnum.VALIDATION_FAILED);
    }
}
