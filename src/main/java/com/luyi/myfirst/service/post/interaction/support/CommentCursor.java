package com.luyi.myfirst.service.post.interaction.support;

import com.luyi.myfirst.common.exception.AuthErrorEnum;
import com.luyi.myfirst.common.exception.BizException;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;

/**
 * 评论列表游标：{@code (created_at, id)} 二元组的不透明编解码。
 *
 * 评论排序固定为创建时间倒序，因此游标不需要携带排序维度。
 */
public record CommentCursor(Instant createdAt, long id) {

    private static final Base64.Encoder ENCODER = Base64.getUrlEncoder().withoutPadding();
    private static final Base64.Decoder DECODER = Base64.getUrlDecoder();
    private static final char SEPARATOR = ':';

    public String encode() {
        String raw = createdAt.toEpochMilli() + String.valueOf(SEPARATOR) + id;
        return ENCODER.encodeToString(raw.getBytes(StandardCharsets.US_ASCII));
    }

    /**
     * 解码；null / 空串表示「从第一页开始」，返回 null。
     *
     * 任何解不开的输入都归 {@code VALIDATION_FAILED}。
     */
    public static CommentCursor decode(String cursor) {
        if (cursor == null || cursor.isBlank()) {
            return null;
        }
        try {
            String raw = new String(DECODER.decode(cursor), StandardCharsets.US_ASCII);
            int separator = raw.indexOf(SEPARATOR);
            if (separator <= 0 || separator == raw.length() - 1) {
                throw new IllegalArgumentException("missing separator");
            }
            Instant createdAt = Instant.ofEpochMilli(Long.parseLong(raw.substring(0, separator)));
            long id = Long.parseLong(raw.substring(separator + 1));
            return new CommentCursor(createdAt, id);
        } catch (IllegalArgumentException broken) {
            throw new BizException(AuthErrorEnum.VALIDATION_FAILED);
        }
    }
}
