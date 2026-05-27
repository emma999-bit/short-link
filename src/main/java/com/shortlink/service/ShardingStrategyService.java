package com.shortlink.service;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/**
 * Sharding strategy service: computes database index, table index, and Redis Cluster slot.
 * <p>
 * Database index: ((hashCode & 0x7fffffff) >> 8) % dbCount  (high bits → library)
 * Table index:    (hashCode & 0x7fffffff) % tableCount       (low bits → table)
 * Redis slot:     CRC16(hashTag) % 16384
 */
@Service
public class ShardingStrategyService {

    @Value("${short-link.sharding.database-count:2}")
    private int databaseCount;

    @Value("${short-link.sharding.table-count:4}")
    private int tableCount;

    /**
     * Calculates the shard database index for a given shortCode.
     */
    public int calculateDbIndex(String shortCode) {
        int hash = shortCode.hashCode();
        return ((hash & 0x7fffffff) >> 8) % databaseCount;
    }

    /**
     * Calculates the shard table index for a given shortCode.
     */
    public int calculateTableIndex(String shortCode) {
        int hash = shortCode.hashCode();
        return (hash & 0x7fffffff) % tableCount;
    }

    /**
     * Calculates the Redis Cluster slot for a key, respecting HashTag notation.
     * If the key contains {tag}, only the tag portion is used for CRC16.
     */
    public int calculateSlot(String key) {
        String hashKey = extractHashTag(key);
        return crc16(hashKey) % 16384;
    }

    /**
     * Builds a HashTag Redis key so that all keys for the same shortCode land on the same slot.
     * e.g., "url:{abc123}" and "hash:{abc123}" are co-located.
     */
    public String buildHashTagKey(String prefix, String shortCode) {
        return prefix + ":{" + shortCode + "}";
    }

    /**
     * Extracts the HashTag from a key. Returns the content of the first '{...}' block if present,
     * otherwise returns the full key.
     */
    public String extractHashTag(String key) {
        int start = key.indexOf('{');
        if (start >= 0) {
            int end = key.indexOf('}', start + 1);
            if (end > start + 1) {
                return key.substring(start + 1, end);
            }
        }
        return key;
    }

    /**
     * CRC16/CCITT implementation as used by Redis Cluster.
     */
    public int crc16(String key) {
        int crc = 0x0000;
        for (byte b : key.getBytes(java.nio.charset.StandardCharsets.UTF_8)) {
            for (int i = 0; i < 8; i++) {
                boolean mix = ((crc & 0x8000) >> 8) != 0;
                crc <<= 1;
                if (((b >> (7 - i)) & 0x01) == 1) {
                    crc ^= 0x0001;
                }
                if (mix) {
                    crc ^= 0x1021;
                }
            }
        }
        return crc & 0xFFFF;
    }

    public int getDatabaseCount() {
        return databaseCount;
    }

    public int getTableCount() {
        return tableCount;
    }
}
