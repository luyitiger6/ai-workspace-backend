package com.luyi.myfirst.common.id;

import java.security.SecureRandom;
import java.time.Instant;
import java.util.random.RandomGenerator;

/**
 * ULID 生成（26 字符，Crockford Base32）。
 *
 * 前 10 字符是 48 位毫秒时间戳，后 16 字符是 80 位随机。字典序即时间序，
 * 于是「按对外标识排序」就近似「按创建时间排序」，不必为了分页再补一个时间条件。
 *
 * 不保证同一毫秒内单调递增：那需要在进程内保存上一次的随机段并做进位，
 * 而对外标识只要求唯一、不可猜，不要求同毫秒有序——用可变状态换这一点排序收益不值得。
 */
public final class Ulid {

    /** Crockford Base32：去掉 I、L、O、U，避免与 1、0 手写混淆。 */
    private static final char[] ALPHABET = "0123456789ABCDEFGHJKMNPQRSTVWXYZ".toCharArray();

    private static final int LENGTH = 26;
    private static final int TIME_CHARS = 10;
    private static final int RANDOM_CHARS = LENGTH - TIME_CHARS;

    /** 48 位时间戳的上界；超出这个范围的时刻无法编码。 */
    private static final long MAX_MILLIS = (1L << 48) - 1;

    private static final SecureRandom RANDOM = new SecureRandom();

    private Ulid() {
    }

    public static String generate() {
        return generate(Instant.now(), RANDOM);
    }

    /**
     * 指定时刻与随机源的生成入口。
     *
     * 时间与随机都必须能从外部喂入：长度、字符集、时间前缀与字典序这些性质，
     * 只有把「现在」和「随机」都固定住才断言得干净。
     */
    static String generate(Instant at, RandomGenerator random) {
        long millis = at.toEpochMilli();
        if (millis < 0 || millis > MAX_MILLIS) {
            throw new IllegalArgumentException("时间戳超出 ULID 的 48 位范围: " + millis);
        }

        char[] chars = new char[LENGTH];
        for (int i = 0; i < TIME_CHARS; i++) {
            // 10 个字符 * 5 位 = 50 位，比 48 位多两位：首字符只承载高 3 位，恒落在 0-7。
            int shift = (TIME_CHARS - 1 - i) * 5;
            chars[i] = ALPHABET[(int) ((millis >>> shift) & 0x1F)];
        }
        for (int i = 0; i < RANDOM_CHARS; i++) {
            chars[TIME_CHARS + i] = ALPHABET[random.nextInt(ALPHABET.length)];
        }
        return new String(chars);
    }
}
