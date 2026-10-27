package com.luyi.myfirst.common.security;

import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Component;

/**
 * BCrypt 实现：强度 10，盐由 BCrypt 生成并内嵌在哈希串里。
 *
 * 不自己拼 salt + SHA：盐生成、迭代成本、常量时间比对三件事任何一件写错都不会报错，
 * 只会安静地变弱。BCryptPasswordEncoder 已经把这三件做对了。
 *
 * 72 字节上限由入参校验（8–72 字节）负责，这一层不重复拦截。
 */
@Component
public class BcryptPasswordHasher implements PasswordHasher {

    private static final int STRENGTH = 10;

    private final BCryptPasswordEncoder encoder = new BCryptPasswordEncoder(STRENGTH);

    @Override
    public String hash(String rawPassword) {
        if (rawPassword == null || rawPassword.isEmpty()) {
            throw new IllegalArgumentException("密码不能为空");
        }
        return encoder.encode(rawPassword);
    }

    @Override
    public boolean matches(String rawPassword, String encodedPassword) {
        if (rawPassword == null || encodedPassword == null || encodedPassword.isEmpty()) {
            return false;
        }
        return encoder.matches(rawPassword, encodedPassword);
    }
}
