package com.luyi.myfirst.service.auth.support;

import java.util.Locale;

/**
 * 邮箱规范化（spec「注册入参与邮箱规范化」）。
 *
 * 唯一性与缓存键都必须建立在规范化之后的值上：否则 A@Example.com 与 a@example.com
 * 会是两个账户、两个注册码，而用户以为它们是同一个。
 */
public final class EmailNormalizer {

    private EmailNormalizer() {
    }

    public static String normalize(String email) {
        return email == null ? null : email.trim().toLowerCase(Locale.ROOT);
    }
}
