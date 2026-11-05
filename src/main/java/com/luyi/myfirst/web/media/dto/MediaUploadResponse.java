package com.luyi.myfirst.web.media.dto;

/**
 * 上传成功的响应体。
 *
 * 只有三项，且只用 {@code String} 与 {@code long}：契约闸门对映射表之外的类型
 * （嵌套类型、集合泛型）是 fail-closed 判红，这里刻意不给它判红的机会。
 * 字段名必须与前端 {@code frontend/lib/contracts/media.ts} 逐字一致。
 */
public record MediaUploadResponse(String url, String contentType, long size) {
}
