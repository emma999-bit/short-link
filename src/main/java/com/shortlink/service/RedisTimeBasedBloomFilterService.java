package com.shortlink.service;

import org.redisson.api.RBitSet;
import org.redisson.api.RedissonClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;

/**
 * Redis time-based Bloom filter service (second tier).
 * Creates time-sliced Bloom filter keys in Redis: bloom:slice:{yyyyMMddHH}
 * <p>
 * Uses bit-hashing to simulate a Bloom filter via Redis BITSET.
 * Scheduled cleanup removes expired slices older than maxSlices * timeSliceHours.
 */
@Service
public class RedisTimeBasedBloomFilterService {

    private static final Logger log = LoggerFactory.getLogger(RedisTimeBasedBloomFilterService.class);
    private static final DateTimeFormatter SLICE_FORMAT = DateTimeFormatter.ofPattern("yyyyMMddHH");

    // Bloom filter parameters
    private static final int BIT_ARRAY_SIZE = 8_388_608; // 8MB = 8*1024*1024 bits
    private static final int HASH_COUNT = 7; // number of hash functions

    @Value("${short-link.bloom-filter.time-slice-hours:24}")
    private int timeSliceHours;

    @Value("${short-link.bloom-filter.max-slices:7}")
    private int maxSlices;

    private final RedissonClient redisson;

    public RedisTimeBasedBloomFilterService(RedissonClient redisson) {
        this.redisson = redisson;
    }

    /**
     * Adds a short code to the current time slice's Bloom filter in Redis.
     */
    public void put(String shortCode) {
        String sliceKey = currentSliceKey();
        RBitSet bitSet = redisson.getBitSet(sliceKey);
        for (long bitIndex : getBitIndexes(shortCode)) {
            bitSet.set(bitIndex, true);
        }
    }

    /**
     * Checks whether the short code might exist in any active time slice.
     * Returns false only if not in any slice.
     */
    public boolean mightContain(String shortCode) {
        List<String> sliceKeys = getActiveSliceKeys();
        long[] bitIndexes = getBitIndexes(shortCode);
        for (String sliceKey : sliceKeys) {
            RBitSet bitSet = redisson.getBitSet(sliceKey);
            boolean allSet = true;
            for (long bitIndex : bitIndexes) {
                if (!bitSet.get(bitIndex)) {
                    allSet = false;
                    break;
                }
            }
            if (allSet) return true;
        }
        return false;
    }

    /**
     * Scheduled cleanup: runs every 5 minutes.
     * Removes Bloom filter slices older than maxSlices * timeSliceHours.
     */
    @Scheduled(fixedDelay = 300_000)
    public void cleanupExpiredSlices() {
        try {
            LocalDateTime cutoff = LocalDateTime.now()
                    .minusHours((long) maxSlices * timeSliceHours);
            // We can't enumerate keys from within a cluster easily, so we delete by known pattern
            // Delete slices older than the cutoff using hour-by-hour generation
            LocalDateTime candidate = cutoff.minusHours(1);
            int deleted = 0;
            // Try to delete up to 30 extra-old slices
            for (int i = 0; i < 30; i++) {
                String sliceKey = "bloom:slice:" + candidate.format(SLICE_FORMAT);
                RBitSet bitSet = redisson.getBitSet(sliceKey);
                if (bitSet.isExists()) {
                    bitSet.delete();
                    deleted++;
                    log.info("Deleted expired bloom slice: {}", sliceKey);
                }
                candidate = candidate.minusHours(1);
            }
            if (deleted > 0) {
                log.info("Cleaned up {} expired bloom filter slices", deleted);
            }
        } catch (Exception e) {
            log.warn("Error during bloom filter slice cleanup: {}", e.getMessage());
        }
    }

    /**
     * Returns the current time slice key.
     */
    public String currentSliceKey() {
        return "bloom:slice:" + LocalDateTime.now().format(SLICE_FORMAT);
    }

    /**
     * Returns all active slice keys (current + past slices within retention window).
     */
    public List<String> getActiveSliceKeys() {
        List<String> keys = new ArrayList<>();
        LocalDateTime now = LocalDateTime.now();
        int totalSlices = maxSlices;
        for (int i = 0; i < totalSlices; i++) {
            keys.add("bloom:slice:" + now.minusHours((long) i * timeSliceHours).format(SLICE_FORMAT));
        }
        return keys;
    }

    /**
     * Computes bit indexes for the given shortCode using multiple hash functions.
     * Simulates a Bloom filter via double-hashing: h_i(x) = h1(x) + i * h2(x)
     */
    private long[] getBitIndexes(String shortCode) {
        long[] indexes = new long[HASH_COUNT];
        byte[] bytes = shortCode.getBytes(java.nio.charset.StandardCharsets.UTF_8);

        // Two base hash values
        long h1 = murmur64(bytes, 0);
        long h2 = murmur64(bytes, 1);

        for (int i = 0; i < HASH_COUNT; i++) {
            long combined = h1 + (long) i * h2;
            indexes[i] = Math.abs(combined % BIT_ARRAY_SIZE);
        }
        return indexes;
    }

    /**
     * A simple 64-bit Murmur-style hash (seed-based).
     */
    private long murmur64(byte[] data, long seed) {
        long h = seed ^ (data.length * 0xc4ceb9fe1a85ec53L);
        for (byte b : data) {
            h ^= (b & 0xFFL);
            h *= 0xff51afd7ed558ccdL;
            h ^= h >>> 33;
        }
        h ^= h >>> 33;
        h *= 0xc4ceb9fe1a85ec53L;
        h ^= h >>> 33;
        return h;
    }
}
