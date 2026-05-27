package com.shortlink.service;

import com.alibaba.csp.sentinel.annotation.SentinelResource;
import com.alibaba.csp.sentinel.slots.block.BlockException;
import com.shortlink.dao.ShortUrlDao;
import com.shortlink.dto.CacheCheckResult;
import com.shortlink.dto.CreateShortUrlRequest;
import com.shortlink.dto.CreateShortUrlResponse;
import com.shortlink.entity.ShortUrlMapping;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.Optional;

/**
 * Core short URL business service.
 * <p>
 * Write path: Multi-hash candidates → smart cache check → distributed lock
 *             → pre-check DB → transactional save → back-fill Bloom + Cache
 * <p>
 * Read path: Bloom filter → local cache → Redis → DB → back-fill
 */
@Service
public class ShortUrlService {

    private static final Logger log = LoggerFactory.getLogger(ShortUrlService.class);

    @Value("${server.port:8080}")
    private int serverPort;

    @Value("${spring.application.name:short-link}")
    private String appName;

    private final ShortCodeService shortCodeService;
    private final TieredBloomFilterService bloomFilter;
    private final LocalCacheService localCache;
    private final ClusterAwareCacheService clusterCache;
    private final DistributedLockService lockService;
    private final ShortUrlDao shortUrlDao;
    private final BloomFilterStreamService bloomStream;
    private final LocalCacheStreamService cacheStream;
    private final MeterRegistry meterRegistry;

    public ShortUrlService(ShortCodeService shortCodeService,
                           TieredBloomFilterService bloomFilter,
                           LocalCacheService localCache,
                           ClusterAwareCacheService clusterCache,
                           DistributedLockService lockService,
                           ShortUrlDao shortUrlDao,
                           BloomFilterStreamService bloomStream,
                           LocalCacheStreamService cacheStream,
                           MeterRegistry meterRegistry) {
        this.shortCodeService = shortCodeService;
        this.bloomFilter = bloomFilter;
        this.localCache = localCache;
        this.clusterCache = clusterCache;
        this.lockService = lockService;
        this.shortUrlDao = shortUrlDao;
        this.bloomStream = bloomStream;
        this.cacheStream = cacheStream;
        this.meterRegistry = meterRegistry;
    }

    /**
     * Creates a short URL for the given request.
     * <p>
     * Flow:
     * 1. Compute URL hash for deduplication
     * 2. Smart cache check (local → Redis → DB broadcast)
     * 3. If existing: return the cached mapping
     * 4. Generate candidate short codes (multi-hash + snowflake fallback)
     * 5. Acquire distributed lock on URL hash
     * 6. Re-check inside lock (double-checked locking)
     * 7. Pre-check specific shard via raw datasource
     * 8. Save to DB with unique constraint self-healing
     * 9. Back-fill Bloom filter + caches
     * 10. Broadcast via Redis Stream
     */
    @SentinelResource(value = "createShortUrl", blockHandler = "createShortUrlFallback")
    public CreateShortUrlResponse createShortUrl(CreateShortUrlRequest request) {
        Timer.Sample timerSample = Timer.start(meterRegistry);
        String urlHash = shortCodeService.computeUrlHash(request.getOriginUrl());

        try {
            MDC.put("urlHash", urlHash);

            // Step 1: Smart cache check for deduplication
            CacheCheckResult existing = smartCacheCheck(urlHash, request.getOriginUrl());
            if (existing.isHit()) {
                meterRegistry.counter("shortlink.create.dedup", "source", existing.getSource().name()).increment();
                log.info("Dedup hit for urlHash={} source={}", urlHash, existing.getSource());
                return buildResponse(existing.getShortCode(), request.getOriginUrl(), true, null);
            }

            // Step 2: Generate candidates
            String[] candidates = request.getCustomCode() != null
                    ? new String[]{request.getCustomCode()}
                    : shortCodeService.generateCandidates(request.getOriginUrl());

            // Step 3: Acquire distributed lock on URL hash
            String lockKey = lockService.buildUrlHashLockKey(urlHash);
            CreateShortUrlResponse result = lockService.withLock(lockKey, () -> {
                // Double-checked locking: re-check inside lock
                CacheCheckResult recheck = smartCacheCheck(urlHash, request.getOriginUrl());
                if (recheck.isHit()) {
                    meterRegistry.counter("shortlink.create.dedup", "source", "LOCK_RECHECK").increment();
                    return buildResponse(recheck.getShortCode(), request.getOriginUrl(), true, null);
                }

                // Step 4: Find a non-colliding short code
                String shortCode = resolveShortCode(candidates, request.getOriginUrl());
                if (shortCode == null) {
                    throw new RuntimeException("All short code candidates are taken");
                }

                // Step 5: Persist to DB
                ShortUrlMapping mapping = ShortUrlMapping.builder()
                        .id(System.currentTimeMillis()) // ShardingSphere will override with SNOWFLAKE if configured
                        .shortCode(shortCode)
                        .originUrl(request.getOriginUrl())
                        .originUrlHash(urlHash)
                        .createTime(LocalDateTime.now())
                        .updateTime(LocalDateTime.now())
                        .expireDays(request.getExpireDays())
                        .accessCount(0L)
                        .status(1)
                        .creator(request.getCreator())
                        .build();

                ShortUrlMapping saved = shortUrlDao.save(mapping);

                // Step 6: Back-fill caches and Bloom filter
                backfillCaches(saved.getShortCode(), request.getOriginUrl(), urlHash);

                meterRegistry.counter("shortlink.create.success").increment();
                log.info("Created shortCode={} for urlHash={}", saved.getShortCode(), urlHash);
                return buildResponse(saved.getShortCode(), request.getOriginUrl(), false, saved.getCreateTime());
            });

            if (result == null) {
                throw new RuntimeException("Failed to acquire lock for URL hash: " + urlHash);
            }
            return result;

        } finally {
            timerSample.stop(meterRegistry.timer("shortlink.create.duration"));
            MDC.remove("urlHash");
        }
    }

    /**
     * Resolves the original URL for a given short code.
     * <p>
     * Read path: Bloom → local cache → Redis → DB → back-fill
     */
    @SentinelResource(value = "resolveShortUrl", blockHandler = "resolveShortUrlFallback")
    public Optional<String> resolveShortUrl(String shortCode) {
        Timer.Sample timerSample = Timer.start(meterRegistry);
        MDC.put("shortCode", shortCode);

        try {
            // Step 1: Bloom filter check (fast negative)
            if (!bloomFilter.mightContain(shortCode)) {
                meterRegistry.counter("shortlink.resolve.miss", "stage", "bloom").increment();
                return Optional.empty();
            }

            // Step 2: Local Caffeine cache
            String localResult = localCache.getByShortCode(shortCode);
            if (localResult != null) {
                meterRegistry.counter("shortlink.resolve.hit", "stage", "local").increment();
                handleAccessCount(shortCode);
                return Optional.of(localResult);
            }

            // Step 3: Redis Cluster cache
            String redisResult = clusterCache.getFromCache(shortCode);
            if (redisResult != null) {
                // Back-fill local cache
                localCache.safePut(shortCode, redisResult);
                meterRegistry.counter("shortlink.resolve.hit", "stage", "redis").increment();
                handleAccessCount(shortCode);
                return Optional.of(redisResult);
            }

            // Step 4: Database (ShardingSphere routes to correct shard)
            Optional<ShortUrlMapping> dbResult = shortUrlDao.findByShortCode(shortCode);
            if (dbResult.isPresent()) {
                String originUrl = dbResult.get().getOriginUrl();
                // Back-fill all cache tiers
                backfillCaches(shortCode, originUrl, dbResult.get().getOriginUrlHash());
                meterRegistry.counter("shortlink.resolve.hit", "stage", "db").increment();
                handleAccessCount(shortCode);
                return Optional.of(originUrl);
            }

            meterRegistry.counter("shortlink.resolve.miss", "stage", "db").increment();
            return Optional.empty();

        } finally {
            timerSample.stop(meterRegistry.timer("shortlink.resolve.duration"));
            MDC.remove("shortCode");
        }
    }

    /**
     * Smart cache check: looks up by urlHash in local cache → Redis → DB broadcast.
     */
    private CacheCheckResult smartCacheCheck(String urlHash, String originUrl) {
        // Check local URL hash cache
        String localShortCode = localCache.getShortCodeByUrlHash(urlHash);
        if (localShortCode != null) {
            return CacheCheckResult.hit(CacheCheckResult.Source.LOCAL_CACHE, localShortCode, originUrl);
        }

        // Check Redis URL hash mapping
        String redisShortCode = clusterCache.getShortCodeByUrlHash(urlHash);
        if (redisShortCode != null) {
            localCache.putUrlHash(urlHash, redisShortCode);
            return CacheCheckResult.hit(CacheCheckResult.Source.REDIS, redisShortCode, originUrl);
        }

        // Check DB (broadcast across shards since urlHash is not the shard key)
        Optional<ShortUrlMapping> dbResult = shortUrlDao.findByUrlHash(urlHash);
        if (dbResult.isPresent()) {
            String shortCode = dbResult.get().getShortCode();
            backfillCaches(shortCode, originUrl, urlHash);
            return CacheCheckResult.hit(CacheCheckResult.Source.DB, shortCode, originUrl);
        }

        return CacheCheckResult.miss();
    }

    /**
     * Finds a non-colliding short code from the candidate list.
     * Uses pre-check on specific shard before full DB check.
     */
    private String resolveShortCode(String[] candidates, String originUrl) {
        for (String candidate : candidates) {
            // Pre-check via raw datasource (bypass ShardingSphere)
            boolean exists = shortUrlDao.preCheckByShortCode(candidate);
            if (!exists) {
                // Also check Bloom filter as secondary gate
                if (!bloomFilter.mightContain(candidate)) {
                    return candidate;
                }
                // Bloom says might exist, do full JPA check
                if (shortUrlDao.findByShortCode(candidate).isEmpty()) {
                    return candidate;
                }
                log.debug("Candidate shortCode={} collides, trying next", candidate);
            }
        }
        return null;
    }

    /**
     * Back-fills Bloom filter, local cache, and Redis cache for a shortCode → originUrl mapping.
     * Also broadcasts via Redis Streams for cross-node sync.
     */
    private void backfillCaches(String shortCode, String originUrl, String urlHash) {
        // Bloom filter
        bloomFilter.put(shortCode);
        bloomStream.publishAdd(shortCode);

        // Local cache
        localCache.safePut(shortCode, originUrl);
        if (urlHash != null) {
            localCache.putUrlHash(urlHash, shortCode);
        }

        // Redis cache
        clusterCache.putToCache(shortCode, originUrl);
        if (urlHash != null) {
            clusterCache.putUrlHashMapping(urlHash, shortCode);
        }

        // Broadcast to other nodes
        cacheStream.publishPut(shortCode, originUrl);
    }

    /**
     * Handles access count: increments Redis counter and flushes to DB every flushThreshold hits.
     */
    private void handleAccessCount(String shortCode) {
        try {
            long count = clusterCache.incrementAccessCount(shortCode);
            if (clusterCache.shouldFlushAccessCount(count)) {
                // Async flush to DB
                flushAccessCountToDb(shortCode, count);
            }
        } catch (Exception e) {
            log.warn("Failed to increment access count for shortCode={}: {}", shortCode, e.getMessage());
        }
    }

    @Transactional
    protected void flushAccessCountToDb(String shortCode, long countInRedis) {
        try {
            shortUrlDao.incrementAccessCount(shortCode, clusterCache.getFlushThreshold());
            log.debug("Flushed access count to DB for shortCode={} delta={}", shortCode,
                    clusterCache.getFlushThreshold());
        } catch (Exception e) {
            log.warn("Failed to flush access count to DB for shortCode={}: {}", shortCode, e.getMessage());
        }
    }

    private CreateShortUrlResponse buildResponse(String shortCode, String originUrl,
                                                  boolean existing, LocalDateTime createTime) {
        return CreateShortUrlResponse.builder()
                .shortCode(shortCode)
                .shortUrl("http://localhost:" + serverPort + "/" + shortCode)
                .originUrl(originUrl)
                .createTime(createTime != null ? createTime : LocalDateTime.now())
                .existing(existing)
                .build();
    }

    // ---- Sentinel fallback handlers ----

    public CreateShortUrlResponse createShortUrlFallback(CreateShortUrlRequest request, BlockException ex) {
        log.warn("createShortUrl rate limited: {}", ex.getMessage());
        meterRegistry.counter("shortlink.sentinel.blocked", "resource", "createShortUrl").increment();
        throw new com.shortlink.exception.RateLimitException("Too many requests: createShortUrl");
    }

    public Optional<String> resolveShortUrlFallback(String shortCode, BlockException ex) {
        log.warn("resolveShortUrl rate limited for shortCode={}: {}", shortCode, ex.getMessage());
        meterRegistry.counter("shortlink.sentinel.blocked", "resource", "resolveShortUrl").increment();
        throw new com.shortlink.exception.RateLimitException("Too many requests: resolveShortUrl");
    }
}
