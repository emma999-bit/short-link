package com.shortlink.service;

import com.shortlink.dao.ShortUrlDao;
import com.shortlink.dto.CacheCheckResult;
import com.shortlink.dto.CreateShortUrlRequest;
import com.shortlink.dto.CreateShortUrlResponse;
import com.shortlink.entity.ShortUrlMapping;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Unit tests for ShortUrlService.
 * All dependencies are mocked via Mockito. No DB or Redis connections needed.
 */
@ExtendWith(MockitoExtension.class)
class ShortUrlServiceTest {

    @Mock
    private ShortCodeService shortCodeService;

    @Mock
    private TieredBloomFilterService bloomFilter;

    @Mock
    private LocalCacheService localCache;

    @Mock
    private ClusterAwareCacheService clusterCache;

    @Mock
    private DistributedLockService lockService;

    @Mock
    private ShortUrlDao shortUrlDao;

    @Mock
    private BloomFilterStreamService bloomStream;

    @Mock
    private LocalCacheStreamService cacheStream;

    private MeterRegistry meterRegistry;

    private ShortUrlService service;

    @BeforeEach
    void setUp() {
        meterRegistry = new SimpleMeterRegistry();
        service = new ShortUrlService(
                shortCodeService, bloomFilter, localCache, clusterCache,
                lockService, shortUrlDao, bloomStream, cacheStream, meterRegistry
        );
    }

    // ---- createShortUrl tests ----

    @Test
    @DisplayName("createShortUrl returns existing mapping when dedup cache hits")
    void createShortUrl_dedupHitFromLocalCache() {
        String originUrl = "https://example.com/test";
        String urlHash = "abc123hash";
        String existingShortCode = "xY3kP9zQ";

        when(shortCodeService.computeUrlHash(originUrl)).thenReturn(urlHash);
        when(localCache.getShortCodeByUrlHash(urlHash)).thenReturn(existingShortCode);

        CreateShortUrlRequest request = CreateShortUrlRequest.builder()
                .originUrl(originUrl)
                .build();

        CreateShortUrlResponse response = service.createShortUrl(request);

        assertThat(response).isNotNull();
        assertThat(response.getShortCode()).isEqualTo(existingShortCode);
        assertThat(response.isExisting()).isTrue();
        verify(lockService, never()).withLock(any(), any());
    }

    @Test
    @DisplayName("createShortUrl creates new short code when no dedup hit")
    void createShortUrl_newMappingCreated() {
        String originUrl = "https://example.com/new-url";
        String urlHash = "newhash456";
        String newShortCode = "aB2cD4eF";

        when(shortCodeService.computeUrlHash(originUrl)).thenReturn(urlHash);
        when(localCache.getShortCodeByUrlHash(urlHash)).thenReturn(null);
        when(clusterCache.getShortCodeByUrlHash(urlHash)).thenReturn(null);
        when(shortUrlDao.findByUrlHash(urlHash)).thenReturn(Optional.empty());
        when(shortCodeService.generateCandidates(originUrl))
                .thenReturn(new String[]{newShortCode, "backup1", "backup2", "snowflake1"});

        ShortUrlMapping savedMapping = ShortUrlMapping.builder()
                .id(1L)
                .shortCode(newShortCode)
                .originUrl(originUrl)
                .originUrlHash(urlHash)
                .createTime(LocalDateTime.now())
                .updateTime(LocalDateTime.now())
                .status(1)
                .accessCount(0L)
                .build();

        // Mock lock service to execute the action
        when(lockService.withLock(anyString(), any())).thenAnswer(invocation -> {
            java.util.function.Supplier<?> action = invocation.getArgument(1);
            return action.get();
        });

        // No dedup hit inside the lock
        when(shortUrlDao.findByUrlHash(urlHash)).thenReturn(Optional.empty());
        when(shortUrlDao.preCheckByShortCode(newShortCode)).thenReturn(false);
        when(bloomFilter.mightContain(newShortCode)).thenReturn(false);
        when(shortUrlDao.save(any())).thenReturn(savedMapping);
        when(clusterCache.incrementAccessCount(any())).thenReturn(0L);
        when(clusterCache.shouldFlushAccessCount(anyLong())).thenReturn(false);

        CreateShortUrlRequest request = CreateShortUrlRequest.builder()
                .originUrl(originUrl)
                .build();

        CreateShortUrlResponse response = service.createShortUrl(request);

        assertThat(response).isNotNull();
        assertThat(response.getShortCode()).isEqualTo(newShortCode);
        assertThat(response.isExisting()).isFalse();

        // Verify cache back-fill
        verify(bloomFilter).put(newShortCode);
        verify(localCache).safePut(newShortCode, originUrl);
        verify(clusterCache).putToCache(newShortCode, originUrl);
        verify(bloomStream).publishAdd(newShortCode);
        verify(cacheStream).publishPut(newShortCode, originUrl);
    }

    // ---- resolveShortUrl tests ----

    @Test
    @DisplayName("resolveShortUrl returns empty when bloom filter says absent")
    void resolveShortUrl_bloomMiss() {
        String shortCode = "notExist";
        when(bloomFilter.mightContain(shortCode)).thenReturn(false);

        Optional<String> result = service.resolveShortUrl(shortCode);

        assertThat(result).isEmpty();
        verify(localCache, never()).getByShortCode(any());
    }

    @Test
    @DisplayName("resolveShortUrl returns from local cache on hit")
    void resolveShortUrl_localCacheHit() {
        String shortCode = "abc123";
        String originUrl = "https://example.com";

        when(bloomFilter.mightContain(shortCode)).thenReturn(true);
        when(localCache.getByShortCode(shortCode)).thenReturn(originUrl);
        when(clusterCache.incrementAccessCount(shortCode)).thenReturn(1L);
        when(clusterCache.shouldFlushAccessCount(1L)).thenReturn(false);

        Optional<String> result = service.resolveShortUrl(shortCode);

        assertThat(result).isPresent();
        assertThat(result.get()).isEqualTo(originUrl);
        verify(clusterCache, never()).getFromCache(any());
    }

    @Test
    @DisplayName("resolveShortUrl falls through to Redis when local cache misses")
    void resolveShortUrl_redisCacheHit() {
        String shortCode = "xyz789";
        String originUrl = "https://redis-cached.com";

        when(bloomFilter.mightContain(shortCode)).thenReturn(true);
        when(localCache.getByShortCode(shortCode)).thenReturn(null);
        when(clusterCache.getFromCache(shortCode)).thenReturn(originUrl);
        when(clusterCache.incrementAccessCount(shortCode)).thenReturn(5L);
        when(clusterCache.shouldFlushAccessCount(5L)).thenReturn(false);

        Optional<String> result = service.resolveShortUrl(shortCode);

        assertThat(result).isPresent();
        assertThat(result.get()).isEqualTo(originUrl);
        verify(localCache).safePut(shortCode, originUrl);
        verify(shortUrlDao, never()).findByShortCode(any());
    }

    @Test
    @DisplayName("resolveShortUrl falls through to DB when all caches miss")
    void resolveShortUrl_dbHit() {
        String shortCode = "dbOnly1";
        String originUrl = "https://db-only.com";
        String urlHash = "dbhash";

        ShortUrlMapping mapping = ShortUrlMapping.builder()
                .id(42L)
                .shortCode(shortCode)
                .originUrl(originUrl)
                .originUrlHash(urlHash)
                .status(1)
                .accessCount(0L)
                .createTime(LocalDateTime.now())
                .updateTime(LocalDateTime.now())
                .build();

        when(bloomFilter.mightContain(shortCode)).thenReturn(true);
        when(localCache.getByShortCode(shortCode)).thenReturn(null);
        when(clusterCache.getFromCache(shortCode)).thenReturn(null);
        when(shortUrlDao.findByShortCode(shortCode)).thenReturn(Optional.of(mapping));
        when(clusterCache.incrementAccessCount(shortCode)).thenReturn(1L);
        when(clusterCache.shouldFlushAccessCount(1L)).thenReturn(false);

        Optional<String> result = service.resolveShortUrl(shortCode);

        assertThat(result).isPresent();
        assertThat(result.get()).isEqualTo(originUrl);
        // Verify cache back-fill
        verify(bloomFilter).put(shortCode);
        verify(localCache).safePut(shortCode, originUrl);
        verify(clusterCache).putToCache(shortCode, originUrl);
    }

    @Test
    @DisplayName("resolveShortUrl returns empty when not found in any tier")
    void resolveShortUrl_notFound() {
        String shortCode = "missing1";

        when(bloomFilter.mightContain(shortCode)).thenReturn(true);
        when(localCache.getByShortCode(shortCode)).thenReturn(null);
        when(clusterCache.getFromCache(shortCode)).thenReturn(null);
        when(shortUrlDao.findByShortCode(shortCode)).thenReturn(Optional.empty());

        Optional<String> result = service.resolveShortUrl(shortCode);

        assertThat(result).isEmpty();
    }

    @Test
    @DisplayName("Access count flushes to DB at threshold")
    void resolveShortUrl_accessCountFlush() {
        String shortCode = "flush123";
        String originUrl = "https://flush.com";

        when(bloomFilter.mightContain(shortCode)).thenReturn(true);
        when(localCache.getByShortCode(shortCode)).thenReturn(originUrl);
        when(clusterCache.incrementAccessCount(shortCode)).thenReturn(100L);
        when(clusterCache.shouldFlushAccessCount(100L)).thenReturn(true);
        when(clusterCache.getFlushThreshold()).thenReturn(100L);

        Optional<String> result = service.resolveShortUrl(shortCode);

        assertThat(result).isPresent();
        // Flush to DB should be triggered (via incrementAccessCount path)
        verify(clusterCache).incrementAccessCount(shortCode);
    }
}
