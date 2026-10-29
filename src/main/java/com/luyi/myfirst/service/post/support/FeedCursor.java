package com.luyi.myfirst.service.post.support;

import com.luyi.myfirst.common.exception.AuthErrorEnum;
import com.luyi.myfirst.common.exception.BizException;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;

/**
 * 列表游标：{@code (published_at, id)} 二元组的不可读编解码（design D8）。
 *
 * 为什么不能只用 id：feed 的排序键是 {@code published_at}，只带 id 的游标在
 * 「三天前存草稿、今天发布」的帖子上会翻丢。为什么不能只带时间戳：
 * 同一毫秒可能有多条，缺 id 就无法定序，翻页会重复或跳过。
 *
 * 编码只是 Base64，不是加密——游标里没有秘密，混淆只是为了阻止调用方
 * 把它当成可拼装的 offset 参数来用。解不开即 400，不做「尽力猜测」。
 *
 * 精度说明：库列是 {@code DATETIME(3)}，毫秒即全部有效位，所以这里存 epoch 毫秒无损。
 */
public record FeedCursor(Instant publishedAt, long id) {

    private static final Base64.Encoder ENCODER = Base64.getUrlEncoder().withoutPadding();
    private static final Base64.Decoder DECODER = Base64.getUrlDecoder();
    private static final char SEPARATOR = ':';

    public String encode() {
        String raw = publishedAt.toEpochMilli() + String.valueOf(SEPARATOR) + id;
        return ENCODER.encodeToString(raw.getBytes(StandardCharsets.US_ASCII));
    }

    /**
     * 解码；null / 空串表示「从第一页开始」，返回 null。
     *
     * 任何解不开的输入都归 {@code VALIDATION_FAILED}：调用方伪造游标属于参数不合法，
     * 与「没有权限」「资源不存在」是两回事。
     */
    public static FeedCursor decode(String cursor) {
        if (cursor == null || cursor.isBlank()) {
            return null;
        }
        try {
            String raw = new String(DECODER.decode(cursor), StandardCharsets.US_ASCII);
            int separator = raw.indexOf(SEPARATOR);
            if (separator <= 0 || separator == raw.length() - 1) {
                throw new IllegalArgumentException("missing separator");
            }
            Instant publishedAt = Instant.ofEpochMilli(Long.parseLong(raw.substring(0, separator)));
            long id = Long.parseLong(raw.substring(separator + 1));
            return new FeedCursor(publishedAt, id);
        } catch (IllegalArgumentException broken) {
            throw new BizException(AuthErrorEnum.VALIDATION_FAILED);
        }
    }
}
