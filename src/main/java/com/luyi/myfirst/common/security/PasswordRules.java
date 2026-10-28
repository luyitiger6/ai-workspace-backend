package com.luyi.myfirst.common.security;

import java.nio.charset.StandardCharsets;

/**
 * 密码长度约束（spec「注册入参与邮箱规范化」）。
 *
 * 上限 72 是 bcrypt 的硬边界，不是产品口味：bcrypt 只读前 72 字节，超出部分被静默丢弃。
 * 允许更长的密码，等于让用户以为后面那些字符也在保护账户。
 *
 * 因此按 UTF-8 字节数算而不是字符数：一个汉字 3 字节，8 个汉字就够了，
 * 而 8 个汉字如果按字符数算会被误判成「刚好 8 个」的等价物。
 */
public final class PasswordRules {

    public static final int MIN_BYTES = 8;
    public static final int MAX_BYTES = 72;

    private PasswordRules() {
    }

    public static boolean isValid(String rawPassword) {
        if (rawPassword == null) {
            return false;
        }
        int bytes = rawPassword.getBytes(StandardCharsets.UTF_8).length;
        return bytes >= MIN_BYTES && bytes <= MAX_BYTES;
    }
}
