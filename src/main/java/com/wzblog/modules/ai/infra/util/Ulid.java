package com.wzblog.modules.ai.infra.util;

import java.security.SecureRandom;
import java.time.LocalDateTime;
import java.time.ZoneOffset;

/**
 * ULID 生成（26 位 Crockford Base32，可排序不可枚举）。
 */
public final class Ulid {

    private static final char[] ALPHABET = "0123456789ABCDEFGHJKMNPQRSTVWXYZ".toCharArray();
    private static final SecureRandom RANDOM = new SecureRandom();

    private Ulid() {
    }

    public static String next() {
        long time = System.currentTimeMillis();
        char[] value = new char[26];
        for (int i = 9; i >= 0; i--) {
            value[i] = ALPHABET[(int) (time & 0x1F)];
            time >>>= 5;
        }
        byte[] randomness = new byte[10];
        RANDOM.nextBytes(randomness);
        int index = 10;
        for (byte b : randomness) {
            if (index >= 26) {
                break;
            }
            value[index++] = ALPHABET[(b >> 3) & 0x1F];
            if (index < 26) {
                value[index++] = ALPHABET[b & 0x1F];
            }
        }
        return new String(value);
    }

    public static boolean isValid(String value) {
        return value != null && value.matches("^[0-9A-HJKMNP-TV-Z]{26}$");
    }
}
