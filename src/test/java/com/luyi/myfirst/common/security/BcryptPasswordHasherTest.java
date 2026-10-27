package com.luyi.myfirst.common.security;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class BcryptPasswordHasherTest {

    private static final String RAW = "correct-horse-battery";

    private final PasswordHasher hasher = new BcryptPasswordHasher();

    @Test
    @DisplayName("哈希结果不含明文，带 BCrypt 标识，盐内嵌其中")
    void hashHidesPlaintextAndEmbedsSalt() {
        String encoded = hasher.hash(RAW);

        assertThat(encoded).startsWith("$2");
        assertThat(encoded).doesNotContain(RAW);
        // $2a$10$<22 位盐><31 位摘要>：60 字符正是盐与摘要都内嵌的证据，没有独立的 salt 字段
        assertThat(encoded).hasSize(60);
    }

    @Test
    @DisplayName("同一明文两次哈希不同，但两次都能比对通过")
    void samePlaintextYieldsDifferentHashes() {
        String first = hasher.hash(RAW);
        String second = hasher.hash(RAW);

        assertThat(first).isNotEqualTo(second);
        assertThat(hasher.matches(RAW, first)).isTrue();
        assertThat(hasher.matches(RAW, second)).isTrue();
    }

    @Test
    @DisplayName("错误密码、空明文、空哈希、非法格式一律比对失败且不抛异常")
    void rejectsWrongOrMissingInput() {
        String encoded = hasher.hash(RAW);

        assertThat(hasher.matches(RAW + "-x", encoded)).isFalse();
        assertThat(hasher.matches(null, encoded)).isFalse();
        assertThat(hasher.matches(RAW, null)).isFalse();
        assertThat(hasher.matches(RAW, "")).isFalse();
        assertThat(hasher.matches(RAW, "not-a-bcrypt-hash")).isFalse();
    }

    @Test
    @DisplayName("空密码不生成哈希")
    void refusesToHashBlankPassword() {
        assertThatThrownBy(() -> hasher.hash(null)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> hasher.hash("")).isInstanceOf(IllegalArgumentException.class);
    }
}
