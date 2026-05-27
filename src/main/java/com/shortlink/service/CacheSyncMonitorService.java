package com.shortlink.service;

import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.util.HashMap;
import java.util.Map;

/**
 * Cache synchronization monitor service.
 * <p>
 * Monitors local cache stats, Redis stream watermarks, and publishes metrics.
 * Provides a consolidated view of the cache sync health across all nodes.
 */
@Service
public class CacheSyncMonitorService {

    private static final Logger log = LoggerFactory.getLogger(CacheSyncMonitorService.class);

    private final LocalCacheService localCache;
    private final MeterRegistry meterRegistry;

    // Snapshot of last stats for debug API
    private volatile Map<String, Object> lastStats = new HashMap<>();

    public CacheSyncMonitorService(LocalCacheService localCache, MeterRegistry meterRegistry) {
        this.localCache = localCache;
        this.meterRegistry = meterRegistry;
    }

    /**
     * Periodically collects and publishes cache metrics.
     * Runs every 60 seconds.
     */
    @Scheduled(fixedDelay = 60_000)
    public void collectMetrics() {
        try {
            var stats = localCache.stats();
            Map<String, Object> snapshot = new HashMap<>();
            snapshot.put("hitCount", stats.hitCount());
            snapshot.put("missCount", stats.missCount());
            snapshot.put("hitRate", stats.hitRate());
            snapshot.put("evictionCount", stats.evictionCount());
            snapshot.put("loadCount", stats.loadCount());
            snapshot.put("averageLoadPenaltyNanos", stats.averageLoadPenalty());
            this.lastStats = snapshot;

            // Publish to Micrometer
            meterRegistry.gauge("shortlink.local.cache.hit_rate", stats.hitRate());
            meterRegistry.gauge("shortlink.local.cache.eviction_count", stats.evictionCount());

            log.debug("Cache stats: hitRate={} evictions={}", stats.hitRate(), stats.evictionCount());
        } catch (Exception e) {
            log.warn("Error collecting cache metrics: {}", e.getMessage());
        }
    }

    /**
     * Returns the last collected cache stats snapshot.
     */
    public Map<String, Object> getLastStats() {
        return lastStats;
    }
}
