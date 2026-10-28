package com.luyi.myfirst.common.id;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.HashSet;
import java.util.Random;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * ULID 的编码契约。
 *
 * 字母表在测试里独立写一份而不引用实现的常量：引用的话，实现把字母表换成任意 32 个字符，
 * 测试照样通过——而「去掉 I/L/O/U」正是这个标识能被人念对、抄对的原因。
 */
class UlidTest {

    private static final char[] CROCKFORD = "0123456789ABCDEFGHJKMNPQRSTVWXYZ".toCharArray();
    private static final int TIME_CHARS = 10;

    @Test
    @DisplayName("固定时刻：长度 26，前 10 字符解回来就是这个毫秒数")
    void encodesTimestampIntoFirstTenChars() {
        Instant at = Instant.parse("2026-10-28T00:00:00Z");

        String ulid = Ulid.generate(at, new Random(7));

        assertThat(ulid).hasSize(26);
        assertThat(decodeTimestamp(ulid)).isEqualTo(at.toEpochMilli());
    }

    @Test
    @DisplayName("字符集是 Crockford Base32：不含 I、L、O、U")
    void usesCrockfordAlphabet() {
        String ulid = Ulid.generate(Instant.parse("2026-10-28T00:00:00Z"), new Random(11));

        // 正则就是 Crockford 字母表本身：0-9、A-H、J、K、M、N、P-T、V-Z（不含 I/L/O/U）。
        assertThat(ulid).matches("[0-9A-HJKMNP-TV-Z]{26}");
        assertThat(ulid).doesNotContain("I", "L", "O", "U");
    }

    @Test
    @DisplayName("首字符落在 0-7：48 位时间戳在 50 位槽位里，高两位恒为 0")
    void firstCharStaysBelowEight() {
        String ulid = Ulid.generate(Instant.ofEpochMilli(Long.MAX_VALUE >> 17), new Random(3));

        assertThat(ulid.charAt(0)).isBetween('0', '7');
    }

    @Test
    @DisplayName("字典序即时间序：后一毫秒的标识一定排在前面")
    void sortsLexicographicallyByTime() {
        Random random = new Random(5);
        String earlier = Ulid.generate(Instant.ofEpochMilli(1_700_000_000_000L), random);
        String later = Ulid.generate(Instant.ofEpochMilli(1_700_000_000_001L), random);

        assertThat(later).isGreaterThan(earlier);
    }

    @Test
    @DisplayName("同一毫秒内不重复：随机段占 80 位，重复不是靠运气而是靠位宽")
    void staysUniqueWithinSameMillisecond() {
        Instant at = Instant.parse("2026-10-28T00:00:00Z");
        Random random = new Random(13);
        Set<String> ids = new HashSet<>();

        for (int i = 0; i < 1000; i++) {
            ids.add(Ulid.generate(at, random));
        }

        assertThat(ids).hasSize(1000);
    }

    @Test
    @DisplayName("时间戳超出 48 位范围：直接拒绝，不产出无法解码的标识")
    void rejectsTimestampsOutsideFortyEightBits() {
        Instant tooLate = Instant.ofEpochMilli(1L << 48);

        assertThatThrownBy(() -> Ulid.generate(tooLate, new Random(1)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("负时间戳同样拒绝：1970 年之前没有可编码的毫秒偏移")
    void rejectsNegativeTimestamps() {
        Instant beforeEpoch = Instant.ofEpochMilli(-1);

        assertThatThrownBy(() -> Ulid.generate(beforeEpoch, new Random(1)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    private static long decodeTimestamp(String ulid) {
        long value = 0;
        for (int i = 0; i < TIME_CHARS; i++) {
            value = (value << 5) | indexOf(ulid.charAt(i));
        }
        return value;
    }

    private static int indexOf(char c) {
        for (int i = 0; i < CROCKFORD.length; i++) {
            if (CROCKFORD[i] == c) {
                return i;
            }
        }
        throw new AssertionError("不在 Crockford 字母表内: " + c);
    }
}
