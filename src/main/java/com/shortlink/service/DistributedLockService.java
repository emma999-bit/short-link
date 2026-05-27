package com.shortlink.service;

import io.micrometer.core.instrument.MeterRegistry;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Supplier;

/**
 * Distributed lock service built on Redisson.
 * Provides unified tryLock abstraction with alert storm suppression.
 * <p>
 * Features:
 * - Configurable waitSeconds and leaseSeconds
 * - Warn logging when lock acquisition exceeds warnThresholdMs
 * - Alert storm suppression: only logs warning every 60 seconds per lock key
 * - Micrometer counter for lock failures
 */
@Service
public class DistributedLockService {

    private static final Logger log = LoggerFactory.getLogger(DistributedLockService.class);

    @Value("${short-link.lock.wait-seconds:3}")
    private long waitSeconds;

    @Value("${short-link.lock.lease-seconds:10}")
    private long leaseSeconds;

    @Value("${short-link.lock.warn-threshold-ms:1000}")
    private long warnThresholdMs;

    private final RedissonClient redisson;
    private final MeterRegistry meterRegistry;

    // Alert storm suppression: track last warn time per lock key
    private final java.util.concurrent.ConcurrentHashMap<String, Long> lastWarnTime =
            new java.util.concurrent.ConcurrentHashMap<>();
    private static final long WARN_SUPPRESS_MS = 60_000;

    // Counter for consecutive lock failures for alert suppression
    private final AtomicLong lockFailureCount = new AtomicLong(0);

    public DistributedLockService(RedissonClient redisson, MeterRegistry meterRegistry) {
        this.redisson = redisson;
        this.meterRegistry = meterRegistry;
    }

    /**
     * Acquires the lock for the given key, executes the action, and releases it.
     * Returns the action's result, or null if the lock could not be acquired within waitSeconds.
     */
    public <T> T withLock(String lockKey, Supplier<T> action) {
        RLock lock = redisson.getLock(lockKey);
        long startMs = System.currentTimeMillis();
        boolean acquired = false;
        try {
            acquired = lock.tryLock(waitSeconds, leaseSeconds, TimeUnit.SECONDS);
            long waitMs = System.currentTimeMillis() - startMs;

            if (!acquired) {
                recordLockFailure(lockKey);
                return null;
            }

            if (waitMs > warnThresholdMs) {
                warnWithSuppression(lockKey, waitMs);
            }

            lockFailureCount.set(0);
            return action.get();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.warn("Interrupted while waiting for lock: {}", lockKey);
            return null;
        } finally {
            if (acquired && lock.isHeldByCurrentThread()) {
                lock.unlock();
            }
        }
    }

    /**
     * Executes a runnable within the distributed lock.
     * Returns true if the action was executed, false if lock was not acquired.
     */
    public boolean withLockVoid(String lockKey, Runnable action) {
        return Boolean.TRUE.equals(withLock(lockKey, () -> {
            action.run();
            return Boolean.TRUE;
        }));
    }

    /**
     * Builds a standard lock key for a shortCode operation.
     */
    public String buildShortCodeLockKey(String shortCode) {
        return "lock:shortcode:" + shortCode;
    }

    /**
     * Builds a standard lock key for a URL hash operation.
     */
    public String buildUrlHashLockKey(String urlHash) {
        return "lock:urlhash:" + urlHash;
    }

    private void recordLockFailure(String lockKey) {
        log.warn("Failed to acquire lock for key={} after {}s", lockKey, waitSeconds);
        meterRegistry.counter("shortlink.lock.failure", "key", sanitizeKeyForMetric(lockKey))
                .increment();
        lockFailureCount.incrementAndGet();
    }

    private void warnWithSuppression(String lockKey, long waitMs) {
        long now = System.currentTimeMillis();
        Long lastWarn = lastWarnTime.get(lockKey);
        if (lastWarn == null || (now - lastWarn) > WARN_SUPPRESS_MS) {
            log.warn("Slow lock acquisition key={} waitMs={}", lockKey, waitMs);
            lastWarnTime.put(lockKey, now);
        }
    }

    private String sanitizeKeyForMetric(String key) {
        // Truncate to avoid high cardinality in metrics
        return key.length() > 50 ? key.substring(0, 50) : key;
    }
}
