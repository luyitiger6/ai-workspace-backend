package com.luyi.myfirst.web.auth.dto;

import com.luyi.myfirst.common.web.AuthResponseBody;

import java.time.Instant;

/**
 * GET /api/auth/me 的响应体。
 *
 * 五个字段就是 spec 给这个端点划的白名单，多一个少一个都会在字段断言里露出来。
 * 密码哈希、盐、令牌都不在这里——不是从别处删掉的，是这个形状根本装不下它们。
 *
 * status 取枚举的 {@code dbValue()}（小写，与库里的 CHECK 取值一致），
 * 不用枚举名：契约里写的是 {@code active}，不是 {@code ACTIVE}。
 */
public record MeResponse(
        String publicId,
        String email,
        String displayName,
        String status,
        Instant createdAt) implements AuthResponseBody {
}
