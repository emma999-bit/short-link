package com.shortlink.service;

import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * Tiered Bloom filter facade.
 * <p>
 * Tier 1: Local Guava BloomFilter (fast, in-process)
 * Tier 2: Redis time-based Bloom filter slices (distributed, survives restart)
 * <p>
 * mightContain: checks local first, then Redis if local says "no"
 * put: adds to both tiers
 */
@Service
public class TieredBloomFilterService {

    private static final Logger log = LoggerFactory.getLogger(TieredBloomFilterService.class);

    private final LocalBloomFilterService localBloom;
    private final RedisTimeBasedBloomFilterService redisBloom;
    private final MeterRegistry meterRegistry;

    public TieredBloomFilterService(LocalBloomFilterService localBloom,
                                    RedisTimeBasedBloomFilterService redisBloom,
                                    MeterRegistry meterRegistry) {
        this.localBloom = localBloom;
        this.redisBloom = redisBloom;
        this.meterRegistry = meterRegistry;
    }

    /**
     * Tests whether a short code might exist.
     * First checks local Bloom filter (fast path), then falls back to Redis.
     *
     * @return false only when the code definitely does not exist in either tier
     */
    public boolean mightContain(String shortCode) {
        // Tier 1: local Guava filter
        if (localBloom.mightContain(shortCode)) {
            meterRegistry.counter("shortlink.bloom.hit", "tier", "local").increment();
            return true;
        }

        // Tier 2: Redis time-based filter
        try {
            boolean redisResult = redisBloom.mightContain(shortCode);
            if (redisResult) {
                meterRegistry.counter("shortlink.bloom.hit", "tier", "redis").increment();
                // Back-fill local filter on Redis hit
                localBloom.put(shortCode);
                return true;
            }
        } catch (Exception e) {
            // Redis failure: fail open (allow the request through to DB check)
            log.warn("Redis bloom filter check failed for shortCode={}: {}", shortCode, e.getMessage());
            meterRegistry.counter("shortlink.bloom.error", "tier", "redis").increment();
        }

        meterRegistry.counter("shortlink.bloom.miss").increment();
        return false;
    }

    /**
     * Adds a short code to both tiers of the Bloom filter.
     */
    public void put(String shortCode) {
        // Add to local tier
        localBloom.put(shortCode);

        // Add to Redis tier (best effort)
        try {
            redisBloom.put(shortCode);
        } catch (Exception e) {
            log.warn("Failed to put shortCode={} to Redis bloom filter: {}", shortCode, e.getMessage());
        }
    }

    /**
     * Returns the local bloom filter's approximate element count.
     */
    public long approximateElementCount() {
        return localBloom.approximateElementCount();
    }

    /**
     * Returns the local bloom filter's current false positive probability.
     */
    public double expectedFpp() {
        return localBloom.expectedFpp();
    }

    /**
     * Resets the local tier (e.g., on Stream RESET event).
     */
    public void resetLocal() {
        localBloom.reset();
    }
}
