package com.shortlink.util;

import java.util.concurrent.ConcurrentHashMap;

/**
 * Base62 encoding/decoding utility.
 * Uses characters [0-9a-zA-Z] for URL-safe short codes.
 * Caches maxValue calculations for different lengths.
 */
public final class Base62Util {

    private static final char[] CHARS = "0123456789abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ".toCharArray();
    private static final int BASE = CHARS.length; // 62

    /** Cache for maxValue per code length to avoid repeated pow calculations. */
    private static final ConcurrentHashMap<Integer, Long> MAX_VALUE_CACHE = new ConcurrentHashMap<>();

    private Base62Util() {}

    /**
     * Encodes a positive long value to a Base62 string of at least minLength characters
     * (zero-padded on the left).
     */
    public static String encode(long value, int minLength) {
        if (value < 0) {
            throw new IllegalArgumentException("Value must be non-negative: " + value);
        }
        StringBuilder sb = new StringBuilder();
        long v = value;
        while (v > 0) {
            sb.insert(0, CHARS[(int)(v % BASE)]);
            v /= BASE;
        }
        // Pad with '0' to reach minLength
        while (sb.length() < minLength) {
            sb.insert(0, '0');
        }
        return sb.toString();
    }

    /**
     * Encodes a positive long value to a Base62 string (no padding).
     */
    public static String encode(long value) {
        if (value == 0) return "0";
        return encode(value, 1);
    }

    /**
     * Decodes a Base62 string to a long value.
     */
    public static long decode(String encoded) {
        long result = 0;
        for (char c : encoded.toCharArray()) {
            result = result * BASE + charIndex(c);
        }
        return result;
    }

    /**
     * Returns the maximum value representable by a Base62 string of the given length.
     * Cached for performance.
     */
    public static long maxValue(int length) {
        return MAX_VALUE_CACHE.computeIfAbsent(length, l -> {
            long max = 1;
            for (int i = 0; i < l; i++) {
                max *= BASE;
            }
            return max - 1;
        });
    }

    /**
     * Returns whether a value fits in the given length Base62 encoding.
     */
    public static boolean fitsInLength(long value, int length) {
        return value <= maxValue(length);
    }

    private static int charIndex(char c) {
        if (c >= '0' && c <= '9') return c - '0';
        if (c >= 'a' && c <= 'z') return c - 'a' + 10;
        if (c >= 'A' && c <= 'Z') return c - 'A' + 36;
        throw new IllegalArgumentException("Invalid Base62 character: " + c);
    }
}
