package com.shortlink.util;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

/**
 * Cryptographic digest utilities for URL hashing.
 * Provides MD5 (primary hash), SHA-256 (backup hash), and salted hash variants.
 */
public final class DigestUtils {

    private static final char[] HEX_CHARS = "0123456789abcdef".toCharArray();

    private DigestUtils() {}

    /**
     * Computes the MD5 hex string of the given input.
     * Used as primary hash for deduplication.
     */
    public static String md5Hex(String input) {
        return hexDigest("MD5", input);
    }

    /**
     * Computes the SHA-256 hex string of the given input.
     * Used as backup hash when MD5 collides.
     */
    public static String sha256Hex(String input) {
        return hexDigest("SHA-256", input);
    }

    /**
     * Computes a salted MD5 hash: MD5(input + salt).
     * Used for the third hash variant in conflict resolution.
     */
    public static String saltedMd5Hex(String input, String salt) {
        return md5Hex(input + salt);
    }

    /**
     * Returns the first {@code len} characters of the SHA-256 hex digest.
     */
    public static String sha256HexPrefix(String input, int len) {
        String full = sha256Hex(input);
        return full.length() > len ? full.substring(0, len) : full;
    }

    private static String hexDigest(String algorithm, String input) {
        try {
            MessageDigest md = MessageDigest.getInstance(algorithm);
            byte[] bytes = md.digest(input.getBytes(StandardCharsets.UTF_8));
            return toHex(bytes);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("Algorithm not available: " + algorithm, e);
        }
    }

    private static String toHex(byte[] bytes) {
        char[] hex = new char[bytes.length * 2];
        for (int i = 0; i < bytes.length; i++) {
            int b = bytes[i] & 0xFF;
            hex[2 * i] = HEX_CHARS[b >>> 4];
            hex[2 * i + 1] = HEX_CHARS[b & 0x0F];
        }
        return new String(hex);
    }
}
