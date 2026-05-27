package com.shortlink.config;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.cache.CacheManager;
import org.springframework.cache.annotation.EnableCaching;
import org.springframework.cache.caffeine.CaffeineCacheManager;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;

import java.util.concurrent.TimeUnit;

/**
 * Cache configuration for Caffeine local cache.
 * Provides both a Spring CacheManager and a raw Caffeine cache for direct usage.
 */
@Configuration
@EnableCaching
public class CacheConfig {

    @Value("${short-link.cache.local-expire-seconds:300}")
    private long localExpireSeconds;

    @Value("${short-link.cache.local-max-size:10000}")
    private long localMaxSize;

    /**
     * Primary Spring CacheManager backed by Caffeine.
     * Used by @Cacheable / @CacheEvict annotations.
     */
    @Bean
    @Primary
    public CacheManager cacheManager() {
        CaffeineCacheManager manager = new CaffeineCacheManager();
        manager.setCaffeine(Caffeine.newBuilder()
                .maximumSize(localMaxSize)
                .expireAfterWrite(localExpireSeconds, TimeUnit.SECONDS)
                .recordStats());
        return manager;
    }

    /**
     * Raw Caffeine cache for shortCode → originUrl.
     * Keyed by shortCode. Used by LocalCacheService.
     */
    @Bean("shortUrlLocalCache")
    public Cache<String, String> shortUrlLocalCache() {
        return Caffeine.newBuilder()
                .maximumSize(localMaxSize)
                .expireAfterWrite(localExpireSeconds, TimeUnit.SECONDS)
                .recordStats()
                .build();
    }

    /**
     * Raw Caffeine cache for urlHash → shortCode.
     * Keyed by urlHash. Used for deduplication on write path.
     */
    @Bean("urlHashLocalCache")
    public Cache<String, String> urlHashLocalCache() {
        return Caffeine.newBuilder()
                .maximumSize(localMaxSize)
                .expireAfterWrite(localExpireSeconds, TimeUnit.SECONDS)
                .recordStats()
                .build();
    }
}
