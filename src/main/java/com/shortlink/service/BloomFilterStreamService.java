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
 * Redis Stream service for Bloom filter synchronization across cluster nodes.
 * <p>
 * Each node publishes ADD events when a new shortCode is added to its local Bloom filter.
 * All nodes (including self) consume the stream to keep local Bloom filters in sync.
 * <p>
 * Each node uses a unique consumer group: BLOOM_SYNC:{nodeId}
 * This ensures each node processes all events independently.
 */
@Service
public class BloomFilterStreamService {

    private static final Logger log = LoggerFactory.getLogger(BloomFilterStreamService.class);

    private static final String EVENT_TYPE_ADD = "ADD";
    private static final String EVENT_TYPE_RESET = "RESET";
    private static final String FIELD_TYPE = "type";
    private static final String FIELD_SHORT_CODE = "shortCode";
    private static final String FIELD_NODE_ID = "nodeId";

    @Value("${short-link.bloom-filter.stream-key:bloom:sync:stream}")
    private String streamKey;

    @Value("${short-link.bloom-filter.stream-max-len:100000}")
    private int streamMaxLen;

    private final RedissonClient redisson;
    private final LocalBloomFilterService localBloom;

    private String nodeId;
    private String consumerGroup;
    private String consumerName;

    public BloomFilterStreamService(RedissonClient redisson, LocalBloomFilterService localBloom) {
        this.redisson = redisson;
        this.localBloom = localBloom;
    }

    @PostConstruct
    public void init() {
        this.nodeId = buildNodeId();
        this.consumerGroup = "BLOOM_SYNC:" + nodeId;
        this.consumerName = "consumer:" + nodeId;
        ensureConsumerGroup();
        log.info("BloomFilterStreamService initialized nodeId={} group={}", nodeId, consumerGroup);
    }

    /**
     * Publishes an ADD event to the Bloom filter sync stream.
     */
    public void publishAdd(String shortCode) {
        try {
            RStream<String, String> stream = redisson.getStream(streamKey);
            stream.addAsync(
                StreamAddArgs.<String, String>entries(Map.of(
                    FIELD_TYPE, EVENT_TYPE_ADD,
                    FIELD_SHORT_CODE, shortCode,
                    FIELD_NODE_ID, nodeId
                )).trim().maxLen(streamMaxLen).noLimit()
            );
        } catch (Exception e) {
            log.warn("Failed to publish bloom ADD event for shortCode={}: {}", shortCode, e.getMessage());
        }
    }

    /**
     * Publishes a RESET event (triggers full reset on all nodes).
     */
    public void publishReset() {
        try {
            RStream<String, String> stream = redisson.getStream(streamKey);
            stream.add(
                StreamAddArgs.<String, String>entries(Map.of(
                    FIELD_TYPE, EVENT_TYPE_RESET,
                    FIELD_NODE_ID, nodeId
                )).trim().maxLen(streamMaxLen).noLimit()
            );
        } catch (Exception e) {
            log.warn("Failed to publish bloom RESET event: {}", e.getMessage());
        }
    }

    /**
     * Polls the stream for new events and applies them to the local Bloom filter.
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
                processEvent(data);
                stream.ack(consumerGroup, msgId);
            }
        } catch (Exception e) {
            log.debug("Error consuming bloom stream events: {}", e.getMessage());
        }
    }

    /**
     * Stream watermark monitor: if stream length exceeds threshold, trigger emergency trim.
     * Runs every 30 seconds.
     */
    @Scheduled(fixedDelay = 30_000)
    public void monitorStreamWatermark() {
        try {
            RStream<String, String> stream = redisson.getStream(streamKey);
            long size = stream.size();
            if (size > streamMaxLen * 0.9) {
                log.warn("Bloom stream approaching capacity size={} maxLen={}, trimming", size, streamMaxLen);
                stream.trim(StreamTrimArgs.maxLen(streamMaxLen / 2).noLimit());
            }
        } catch (Exception e) {
            log.debug("Error monitoring bloom stream watermark: {}", e.getMessage());
        }
    }

    private void processEvent(Map<String, String> data) {
        String type = data.get(FIELD_TYPE);
        if (EVENT_TYPE_ADD.equals(type)) {
            String shortCode = data.get(FIELD_SHORT_CODE);
            if (shortCode != null && !shortCode.isBlank()) {
                localBloom.put(shortCode);
            }
        } else if (EVENT_TYPE_RESET.equals(type)) {
            log.info("Received RESET event from nodeId={}, resetting local bloom", data.get(FIELD_NODE_ID));
            localBloom.reset();
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
                // Group may already exist; this is fine
                log.debug("Consumer group may already exist: {}", e.getMessage());
            }
        } catch (Exception e) {
            log.warn("Failed to create bloom consumer group: {}", e.getMessage());
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
