package com.luyi.myfirst.common.security;

/**
 * 密码单向哈希。
 *
 * 盐由实现内嵌在返回值里，不单独存储，也不经由任何响应返回
 * （spec「敏感凭证以单向哈希持久化」）。
 * 调用方只做两件事：把明文换成哈希、拿明文去比对；拿不到哈希的内部结构。
 */
public interface PasswordHasher {

    /** 把明文换成可持久化的哈希。 */
    String hash(String rawPassword);

    /**
     * 比对明文与已存哈希。
     * 哈希缺失或格式非法时返回 false 而不是抛异常：登录链路不应因脏数据流出 500。
     */
    boolean matches(String rawPassword, String encodedPassword);
}
