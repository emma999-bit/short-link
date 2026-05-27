package com.shortlink.service;

import com.github.benmanes.caffeine.cache.Cache;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;

/**
 * Local Caffeine cache service for shortCode → originUrl and urlHash → shortCode mappings.
 * <p>
 * Provides safe put/evict operations with null-value protection and logging.
 * Used directly (not via @Cacheable) to allow fine-grained control.
 * For @Cacheable-based cache, see CacheConfig.
 */
@Service
public class LocalCacheService {

    private static final Logger log = LoggerFactory.getLogger(LocalCacheService.class);

    private final Cache<String, String> shortUrlLocalCache;
    private final Cache<String, String> urlHashLocalCache;

    public LocalCacheService(
            @Qualifier("shortUrlLocalCache") Cache<String, String> shortUrlLocalCache,
            @Qualifier("urlHashLocalCache") Cache<String, String> urlHashLocalCache) {
        this.shortUrlLocalCache = shortUrlLocalCache;
        this.urlHashLocalCache = urlHashLocalCache;
    }

    // ---- shortCode → originUrl ----

    /**
     * Safely puts shortCode → originUrl into the local cache.
     * Ignores null values.
     */
    public void safePut(String shortCode, String originUrl) {
        if (shortCode == null || originUrl == null) return;
        shortUrlLocalCache.put(shortCode, originUrl);
        log.debug("LocalCache put shortCode={}", shortCode);
    }

    /**
     * Safely evicts a short code from the local cache.
     */
    public void safeEvict(String shortCode) {
        if (shortCode == null) return;
        shortUrlLocalCache.invalidate(shortCode);
        log.debug("LocalCache evict shortCode={}", shortCode);
    }

    /**
     * Gets the origin URL for a short code from local cache.
     * Returns null if not present.
     */
    public String getByShortCode(String shortCode) {
        if (shortCode == null) return null;
        return shortUrlLocalCache.getIfPresent(shortCode);
    }

    // ---- urlHash → shortCode ----

    /**
     * Safely puts urlHash → shortCode into the URL hash cache.
     */
    public void putUrlHash(String urlHash, String shortCode) {
        if (urlHash == null || shortCode == null) return;
        urlHashLocalCache.put(urlHash, shortCode);
    }

    /**
     * Safely evicts a URL hash from the URL hash cache.
     */
    public void evictUrlHash(String urlHash) {
        if (urlHash == null) return;
        urlHashLocalCache.invalidate(urlHash);
    }

    /**
     * Gets the short code for a URL hash from local cache.
     */
    public String getShortCodeByUrlHash(String urlHash) {
        if (urlHash == null) return null;
        return urlHashLocalCache.getIfPresent(urlHash);
    }

    /**
     * Returns cache statistics for monitoring.
     */
    public com.github.benmanes.caffeine.cache.stats.CacheStats stats() {
        return shortUrlLocalCache.stats();
    }

    /**
     * Invalidates all entries in both local caches.
     */
    public void invalidateAll() {
        shortUrlLocalCache.invalidateAll();
        urlHashLocalCache.invalidateAll();
        log.info("All local caches invalidated");
    }
}
