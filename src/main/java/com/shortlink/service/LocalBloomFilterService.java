package com.shortlink.service;

import com.google.common.hash.BloomFilter;
import com.google.common.hash.Funnels;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;

/**
 * Local Guava BloomFilter service for fast short code existence checks.
 * Provides the first tier of the tiered bloom filter architecture.
 * <p>
 * A positive result means "might exist" (small false positive rate).
 * A negative result means "definitely does not exist".
 */
@Service
public class LocalBloomFilterService {

    private static final Logger log = LoggerFactory.getLogger(LocalBloomFilterService.class);

    @Value("${short-link.bloom-filter.expected-insertions:1000000}")
    private int expectedInsertions;

    @Value("${short-link.bloom-filter.false-probability:0.001}")
    private double falseProbability;

    // The underlying Guava BloomFilter (rebuilt on reset)
    private volatile BloomFilter<String> bloomFilter;

    public LocalBloomFilterService(
            @Value("${short-link.bloom-filter.expected-insertions:1000000}") int expectedInsertions,
            @Value("${short-link.bloom-filter.false-probability:0.001}") double falseProbability) {
        this.expectedInsertions = expectedInsertions;
        this.falseProbability = falseProbability;
        this.bloomFilter = createFilter(expectedInsertions, falseProbability);
    }

    /**
     * Adds a short code to the local bloom filter.
     */
    public void put(String shortCode) {
        bloomFilter.put(shortCode);
    }

    /**
     * Tests whether a short code might exist.
     * Returns false only when the code definitely does not exist.
     */
    public boolean mightContain(String shortCode) {
        return bloomFilter.mightContain(shortCode);
    }

    /**
     * Resets the bloom filter to a fresh empty state.
     * Used during cache sync operations.
     */
    public void reset() {
        log.info("Resetting local bloom filter");
        this.bloomFilter = createFilter(expectedInsertions, falseProbability);
    }

    /**
     * Returns approximate element count (estimated).
     */
    public long approximateElementCount() {
        return bloomFilter.approximateElementCount();
    }

    /**
     * Returns current expected false positive probability.
     */
    public double expectedFpp() {
        return bloomFilter.expectedFpp();
    }

    private BloomFilter<String> createFilter(int insertions, double fpp) {
        return BloomFilter.create(
                Funnels.stringFunnel(StandardCharsets.UTF_8),
                insertions,
                fpp
        );
    }
}
