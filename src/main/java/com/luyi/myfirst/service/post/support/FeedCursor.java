package com.luyi.myfirst.service.post.support;

import com.luyi.myfirst.common.enums.PostSortBy;
import com.luyi.myfirst.common.exception.AuthErrorEnum;
import com.luyi.myfirst.common.exception.BizException;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;

/**
 * 帖子列表游标：自描述排序维度 + 排序键值 + id 的不透明编解码（design D4）。
 *
 * 扩展后支持三种排序：
 * <ul>
 *   <li>{@code publishedAt}：游标携带 {@code (publishedAt, id)}</li>
 *   <li>{@code upVoteCount}：游标携带 {@code (upVoteCount, publishedAt, id)}</li>
 *   <li>{@code commentCount}：游标携带 {@code (commentCount, publishedAt, id)}</li>
 * </ul>
 *
 * 不同排序的游标语义不同，因此编码里带排序维度；调用方把一种排序的游标塞进
 * 另一种排序的请求会被判为非法游标（{@code VALIDATION_FAILED}）。
 *
 * 编码只是 Base64 URL，不是加密——阻止调用方把它当成可拼装的 offset。
 */
public record FeedCursor(PostSortBy sortBy, Instant publishedAt, Long score, long id) {

    private static final Base64.Encoder ENCODER = Base64.getUrlEncoder().withoutPadding();
    private static final Base64.Decoder DECODER = Base64.getUrlDecoder();
    private static final char SEPARATOR = ':';

    public String encode() {
        String raw = switch (sortBy) {
            case PUBLISHED_AT -> sortBy.dbValue() + SEPARATOR + publishedAt.toEpochMilli() + SEPARATOR + id;
            case UP_VOTE_COUNT, COMMENT_COUNT ->
                    sortBy.dbValue() + SEPARATOR + score + SEPARATOR + publishedAt.toEpochMilli() + SEPARATOR + id;
        };
        return ENCODER.encodeToString(raw.getBytes(StandardCharsets.US_ASCII));
    }

    /**
     * 解码；null / 空串表示「从第一页开始」，返回 null。
     *
     * 非法输入、排序维度无法识别、游标段数与排序维度不匹配，一律归 {@code VALIDATION_FAILED}。
     */
    public static FeedCursor decode(String cursor) {
        if (cursor == null || cursor.isBlank()) {
            return null;
        }
        try {
            String raw = new String(DECODER.decode(cursor), StandardCharsets.US_ASCII);
            String[] parts = raw.split(String.valueOf(SEPARATOR), -1);
            PostSortBy sortBy = PostSortBy.fromDbValue(parts[0]).orElseThrow(() -> new IllegalArgumentException("unknown sortBy"));
            return switch (sortBy) {
                case PUBLISHED_AT -> decodePublishedAt(parts);
                case UP_VOTE_COUNT, COMMENT_COUNT -> decodeScore(sortBy, parts);
            };
        } catch (IllegalArgumentException broken) {
            throw new BizException(AuthErrorEnum.VALIDATION_FAILED);
        }
    }

    private static FeedCursor decodePublishedAt(String[] parts) {
        if (parts.length != 3) {
            throw new IllegalArgumentException("publishedAt cursor needs 3 parts");
        }
        Instant publishedAt = Instant.ofEpochMilli(Long.parseLong(parts[1]));
        long id = Long.parseLong(parts[2]);
        return new FeedCursor(PostSortBy.PUBLISHED_AT, publishedAt, null, id);
    }

    private static FeedCursor decodeScore(PostSortBy sortBy, String[] parts) {
        if (parts.length != 4) {
            throw new IllegalArgumentException("score cursor needs 4 parts");
        }
        long score = Long.parseLong(parts[1]);
        Instant publishedAt = Instant.ofEpochMilli(Long.parseLong(parts[2]));
        long id = Long.parseLong(parts[3]);
        return new FeedCursor(sortBy, publishedAt, score, id);
    }
}
