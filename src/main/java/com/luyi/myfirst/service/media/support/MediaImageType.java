package com.luyi.myfirst.service.media.support;

import java.util.Locale;
import java.util.Optional;

/**
 * 受支持的图片格式。
 *
 * 扩展名与 Content-Type 都来自**真实内容**（文件头魔数），不来自客户端声明的
 * {@code Content-Type} 或文件名——那两项都可以随便写。
 */
public enum MediaImageType {

    JPEG("jpg", "image/jpeg"),
    PNG("png", "image/png"),
    WEBP("webp", "image/webp");

    private final String extension;
    private final String contentType;

    MediaImageType(String extension, String contentType) {
        this.extension = extension;
        this.contentType = contentType;
    }

    public String extension() {
        return extension;
    }

    public String contentType() {
        return contentType;
    }

    /** 只看文件头：长度不足或签名不匹配都返回空。 */
    public static Optional<MediaImageType> detect(byte[] head) {
        if (head.length >= 3
                && (head[0] & 0xFF) == 0xFF && (head[1] & 0xFF) == 0xD8 && (head[2] & 0xFF) == 0xFF) {
            return Optional.of(JPEG);
        }
        if (head.length >= 8
                && (head[0] & 0xFF) == 0x89 && head[1] == 'P' && head[2] == 'N' && head[3] == 'G'
                && (head[4] & 0xFF) == 0x0D && (head[5] & 0xFF) == 0x0A
                && (head[6] & 0xFF) == 0x1A && (head[7] & 0xFF) == 0x0A) {
            return Optional.of(PNG);
        }
        // 只判 RIFF 会把 AVI 一起放进来，必须再看第 8–12 字节的 WEBP 标识。
        if (head.length >= 12
                && head[0] == 'R' && head[1] == 'I' && head[2] == 'F' && head[3] == 'F'
                && head[8] == 'W' && head[9] == 'E' && head[10] == 'B' && head[11] == 'P') {
            return Optional.of(WEBP);
        }
        return Optional.empty();
    }

    /** 读取时按已通过形状校验的扩展名还原 Content-Type。 */
    public static Optional<MediaImageType> fromExtension(String extension) {
        String normalized = extension.toLowerCase(Locale.ROOT);
        for (MediaImageType each : values()) {
            if (each.extension.equals(normalized)) {
                return Optional.of(each);
            }
        }
        return Optional.empty();
    }
}
