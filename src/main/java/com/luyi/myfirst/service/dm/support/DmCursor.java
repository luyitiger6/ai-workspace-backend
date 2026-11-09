package com.luyi.myfirst.service.dm.support;

import com.luyi.myfirst.common.exception.AuthErrorEnum;
import com.luyi.myfirst.common.exception.BizException;

import java.nio.charset.StandardCharsets;
import java.util.Base64;

/**
 * 会话内消息游标：单段自增 {@code id} 的不透明编解码（design D4）。
 *
 * 同一会话内消息 {@code id} 严格单调，{@code created_at} 只是它的冗余投影，
 * 所以游标不携带时间戳——{@code idx_dm_messages_conv (conversation_id, id)}
 * 一条索引同时服务排序与游标条件。
 *
 * 编码只是 Base64 URL，不是加密：目的是阻止调用方把它当成可拼装的 offset，
 * 而非法输入一律归 {@code VALIDATION_FAILED}，不泄漏「哪一段错了」。
 */
public record DmCursor(long id) {

    private static final Base64.Encoder ENCODER = Base64.getUrlEncoder().withoutPadding();
    private static final Base64.Decoder DECODER = Base64.getUrlDecoder();

    public String encode() {
        return ENCODER.encodeToString(Long.toString(id).getBytes(StandardCharsets.US_ASCII));
    }

    /**
     * 解码；null / 空串表示「从第一页开始」，返回 {@code null}。
     *
     * Base64 不合法、内容不是十进制数、id 非正数，都判为非法游标。
     */
    public static DmCursor decode(String cursor) {
        if (cursor == null || cursor.isBlank()) {
            return null;
        }
        try {
            String raw = new String(DECODER.decode(cursor), StandardCharsets.US_ASCII);
            long id = Long.parseLong(raw);
            if (id < 1) {
                throw new IllegalArgumentException("cursor id must be positive");
            }
            return new DmCursor(id);
        } catch (IllegalArgumentException broken) {
            throw new BizException(AuthErrorEnum.VALIDATION_FAILED);
        }
    }
}
