package com.shortlink.service;

import io.micrometer.core.instrument.MeterRegistry;
import org.redisson.api.RBatch;
import org.redisson.api.RFuture;
import org.redisson.api.RedissonClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * Redis Cluster-aware cache service.
 * <p>
 * Uses HashTag notation so all related keys for a shortCode land on the same slot.
 * Provides batch operations that group keys by slot and use RBatch for efficiency.
 */
@Service
public class ClusterAwareCacheService {

    private static final Logger log = LoggerFactory.getLogger(ClusterAwareCacheService.class);

    // Key prefixes
    private static final String URL_PREFIX = "url";
    private static final String HASH_PREFIX = "hash";
    private static final String COUNT_PREFIX = "cnt";

    @Value("${short-link.cache.redis-expire-seconds:3600}")
    private long redisExpireSeconds;

    @Value("${short-link.access-count.flush-threshold:100}")
    private long flushThreshold;

    private final StringRedisTemplate redisTemplate;
    private final RedissonClient redisson;
    private final ShardingStrategyService shardingStrategy;
    private final MeterRegistry meterRegistry;

    public ClusterAwareCacheService(StringRedisTemplate redisTemplate,
                                    RedissonClient redisson,
                                    ShardingStrategyService shardingStrategy,
                                    MeterRegistry meterRegistry) {
        this.redisTemplate = redisTemplate;
        this.redisson = redisson;
        this.shardingStrategy = shardingStrategy;
        this.meterRegistry = meterRegistry;
    }

    // ---- URL cache ----

    /**
     * Stores shortCode → originUrl in Redis with HashTag for slot affinity.
     */
    public void putToCache(String shortCode, String originUrl) {
        String key = buildUrlKey(shortCode);
        redisTemplate.opsForValue().set(key, originUrl, Duration.ofSeconds(redisExpireSeconds));
        log.debug("Redis cache put shortCode={} slot={}", shortCode, shardingStrategy.calculateSlot(key));
    }

    /**
     * Gets origin URL for shortCode from Redis.
     */
    public String getFromCache(String shortCode) {
        String key = buildUrlKey(shortCode);
        return redisTemplate.opsForValue().get(key);
    }

    /**
     * Evicts shortCode from Redis cache.
     */
    public void evictFromCache(String shortCode) {
        redisTemplate.delete(buildUrlKey(shortCode));
    }

    // ---- URL Hash mapping ----

    /**
     * Stores urlHash → shortCode mapping for deduplication.
     * Note: URL hash keys use the urlHash as part of a separate key space,
     * but we still use HashTag with shortCode for co-location where possible.
     */
    public void putUrlHashMapping(String urlHash, String shortCode) {
        // Use a separate key space for url hash → short code mapping
        String key = "urlhash:{" + urlHash + "}";
        redisTemplate.opsForValue().set(key, shortCode, Duration.ofSeconds(redisExpireSeconds));
    }

    /**
     * Gets shortCode for the given urlHash from Redis.
     */
    public String getShortCodeByUrlHash(String urlHash) {
        String key = "urlhash:{" + urlHash + "}";
        return redisTemplate.opsForValue().get(key);
    }

    // ---- Access count ----

    /**
     * Atomically increments the access count for a shortCode.
     * Returns the new count.
     */
    public long incrementAccessCount(String shortCode) {
        String key = buildCountKey(shortCode);
        Long count = redisTemplate.opsForValue().increment(key);
        return count != null ? count : 0L;
    }

    /**
     * Gets current access count for a shortCode from Redis.
     */
    public long getAccessCount(String shortCode) {
        String key = buildCountKey(shortCode);
        String val = redisTemplate.opsForValue().get(key);
        if (val == null) return 0L;
        try {
            return Long.parseLong(val);
        } catch (NumberFormatException e) {
            return 0L;
        }
    }

    /**
     * Resets access count in Redis (after DB flush).
     */
    public void resetAccessCount(String shortCode) {
        redisTemplate.delete(buildCountKey(shortCode));
    }

    /**
     * Whether the access count has reached the flush threshold.
     */
    public boolean shouldFlushAccessCount(long count) {
        return count > 0 && count % flushThreshold == 0;
    }

    // ---- Batch operations ----

    /**
     * Batch-gets origin URLs for a list of shortCodes.
     * Groups by slot and uses RBatch for efficient cluster-aware multi-get.
     * Returns a map of shortCode → originUrl (excludes misses).
     */
    public Map<String, String> batchGetFromRedisCluster(List<String> shortCodes) {
        Map<String, String> result = new HashMap<>();
        if (shortCodes == null || shortCodes.isEmpty()) return result;

        // Group short codes by Redis slot
        Map<Integer, List<String>> bySlot = new HashMap<>();
        for (String shortCode : shortCodes) {
            String key = buildUrlKey(shortCode);
            int slot = shardingStrategy.calculateSlot(key);
            bySlot.computeIfAbsent(slot, k -> new ArrayList<>()).add(shortCode);
        }

        // Execute one RBatch per slot group
        for (Map.Entry<Integer, List<String>> entry : bySlot.entrySet()) {
            List<String> group = entry.getValue();
            try {
                RBatch batch = redisson.createBatch();
                List<RFuture<Object>> futures = new ArrayList<>();
                for (String shortCode : group) {
                    futures.add(batch.<Object>getBucket(buildUrlKey(shortCode)).getAsync());
                }
                batch.execute();
                for (int i = 0; i < group.size(); i++) {
                    Object rawVal = futures.get(i).getNow();
                    String val = (rawVal != null) ? rawVal.toString() : null;
                    if (val != null) {
                        result.put(group.get(i), val);
                    }
                }
            } catch (Exception e) {
                log.warn("Batch get failed for slot group, falling back to individual gets: {}", e.getMessage());
                for (String shortCode : group) {
                    String val = getFromCache(shortCode);
                    if (val != null) result.put(shortCode, val);
                }
            }
        }

        return result;
    }

    /**
     * Returns the configured flush threshold for access counts.
     */
    public long getFlushThreshold() {
        return flushThreshold;
    }

    // ---- Key builders ----

    private String buildUrlKey(String shortCode) {
        return shardingStrategy.buildHashTagKey(URL_PREFIX, shortCode);
    }

    private String buildCountKey(String shortCode) {
        return shardingStrategy.buildHashTagKey(COUNT_PREFIX, shortCode);
    }
}
