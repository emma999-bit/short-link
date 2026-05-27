package com.shortlink.service;

import jakarta.annotation.PostConstruct;
import org.redisson.api.RStream;
import org.redisson.api.RedissonClient;
import org.redisson.api.StreamMessageId;
import org.redisson.api.stream.StreamAddArgs;
import org.redisson.api.stream.StreamCreateGroupArgs;
import org.redisson.api.stream.StreamReadGroupArgs;
import org.redisson.api.stream.StreamTrimArgs;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.net.InetAddress;
import java.time.Duration;
import java.util.Map;
import java.util.UUID;

/**
 * Redis Stream service for local cache synchronization across cluster nodes.
 * <p>
 * Publishes PUT and EVICT events so all nodes can keep their local Caffeine caches in sync.
 * PUT events: other nodes fetch from Redis and back-fill their local cache.
 * EVICT events: other nodes invalidate the entry from their local cache.
 */
@Service
public class LocalCacheStreamService {

    private static final Logger log = LoggerFactory.getLogger(LocalCacheStreamService.class);

    private static final String EVENT_TYPE_PUT = "PUT";
    private static final String EVENT_TYPE_EVICT = "EVICT";
    private static final String FIELD_TYPE = "type";
    private static final String FIELD_SHORT_CODE = "shortCode";
    private static final String FIELD_ORIGIN_URL = "originUrl";
    private static final String FIELD_NODE_ID = "nodeId";

    @Value("${short-link.cache.stream-key:cache:sync:stream}")
    private String streamKey;

    @Value("${short-link.cache.stream-max-len:100000}")
    private int streamMaxLen;

    private final RedissonClient redisson;
    private final LocalCacheService localCache;
    private final ClusterAwareCacheService clusterCache;

    private String nodeId;
    private String consumerGroup;
    private String consumerName;

    public LocalCacheStreamService(RedissonClient redisson,
                                   LocalCacheService localCache,
                                   ClusterAwareCacheService clusterCache) {
        this.redisson = redisson;
        this.localCache = localCache;
        this.clusterCache = clusterCache;
    }

    @PostConstruct
    public void init() {
        this.nodeId = buildNodeId();
        this.consumerGroup = "CACHE_SYNC:" + nodeId;
        this.consumerName = "consumer:" + nodeId;
        ensureConsumerGroup();
        log.info("LocalCacheStreamService initialized nodeId={} group={}", nodeId, consumerGroup);
    }

    /**
     * Publishes a PUT event: other nodes will back-fill from Redis.
     */
    public void publishPut(String shortCode, String originUrl) {
        try {
            RStream<String, String> stream = redisson.getStream(streamKey);
            stream.addAsync(
                StreamAddArgs.<String, String>entries(Map.of(
                    FIELD_TYPE, EVENT_TYPE_PUT,
                    FIELD_SHORT_CODE, shortCode,
                    FIELD_ORIGIN_URL, originUrl != null ? originUrl : "",
                    FIELD_NODE_ID, nodeId
                )).trim().maxLen(streamMaxLen).noLimit()
            );
        } catch (Exception e) {
            log.warn("Failed to publish cache PUT event for shortCode={}: {}", shortCode, e.getMessage());
        }
    }

    /**
     * Publishes an EVICT event: other nodes will invalidate this key from local cache.
     */
    public void publishEvict(String shortCode) {
        try {
            RStream<String, String> stream = redisson.getStream(streamKey);
            stream.addAsync(
                StreamAddArgs.<String, String>entries(Map.of(
                    FIELD_TYPE, EVENT_TYPE_EVICT,
                    FIELD_SHORT_CODE, shortCode,
                    FIELD_NODE_ID, nodeId
                )).trim().maxLen(streamMaxLen).noLimit()
            );
        } catch (Exception e) {
            log.warn("Failed to publish cache EVICT event for shortCode={}: {}", shortCode, e.getMessage());
        }
    }

    /**
     * Polls the stream for new events and applies them to local cache.
     * Runs every 2 seconds.
     */
    @Scheduled(fixedDelay = 2000)
    public void consumeEvents() {
        try {
            RStream<String, String> stream = redisson.getStream(streamKey);
            Map<StreamMessageId, Map<String, String>> messages = stream.readGroup(
                consumerGroup, consumerName,
                StreamReadGroupArgs.neverDelivered().count(100).timeout(Duration.ofMillis(100))
            );

            if (messages == null || messages.isEmpty()) return;

            for (Map.Entry<StreamMessageId, Map<String, String>> entry : messages.entrySet()) {
                StreamMessageId msgId = entry.getKey();
                Map<String, String> data = entry.getValue();

                // Skip events published by this node (self-originated)
                String sourceNode = data.get(FIELD_NODE_ID);
                if (nodeId.equals(sourceNode)) {
                    stream.ack(consumerGroup, msgId);
                    continue;
                }

                processEvent(data);
                stream.ack(consumerGroup, msgId);
            }
        } catch (Exception e) {
            log.debug("Error consuming cache stream events: {}", e.getMessage());
        }
    }

    /**
     * Stream watermark monitor: trim if approaching capacity.
     * Runs every 30 seconds.
     */
    @Scheduled(fixedDelay = 30_000)
    public void monitorStreamWatermark() {
        try {
            RStream<String, String> stream = redisson.getStream(streamKey);
            long size = stream.size();
            if (size > streamMaxLen * 0.9) {
                log.warn("Cache stream approaching capacity size={} maxLen={}, trimming", size, streamMaxLen);
                stream.trim(StreamTrimArgs.maxLen(streamMaxLen / 2).noLimit());
            }
        } catch (Exception e) {
            log.debug("Error monitoring cache stream watermark: {}", e.getMessage());
        }
    }

    private void processEvent(Map<String, String> data) {
        String type = data.get(FIELD_TYPE);
        String shortCode = data.get(FIELD_SHORT_CODE);
        if (shortCode == null || shortCode.isBlank()) return;

        if (EVENT_TYPE_PUT.equals(type)) {
            // Back-fill from Redis into local cache
            String originUrl = data.get(FIELD_ORIGIN_URL);
            if (originUrl != null && !originUrl.isBlank()) {
                localCache.safePut(shortCode, originUrl);
            } else {
                // Fetch from Redis to back-fill
                String fetched = clusterCache.getFromCache(shortCode);
                if (fetched != null) {
                    localCache.safePut(shortCode, fetched);
                }
            }
        } else if (EVENT_TYPE_EVICT.equals(type)) {
            localCache.safeEvict(shortCode);
        }
    }

    private void ensureConsumerGroup() {
        try {
            RStream<String, String> stream = redisson.getStream(streamKey);
            try {
                stream.createGroup(
                    StreamCreateGroupArgs.name(consumerGroup)
                        .id(StreamMessageId.NEWEST)
                        .makeStream()
                );
            } catch (Exception e) {
                log.debug("Cache consumer group may already exist: {}", e.getMessage());
            }
        } catch (Exception e) {
            log.warn("Failed to create cache consumer group: {}", e.getMessage());
        }
    }

    private String buildNodeId() {
        String hostname;
        try {
            hostname = InetAddress.getLocalHost().getHostName();
        } catch (Exception e) {
            hostname = "unknown";
        }
        return hostname + ":" + UUID.randomUUID().toString().substring(0, 8);
    }
}
