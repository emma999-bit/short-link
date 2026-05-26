package com.shortlink;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.cache.annotation.EnableCaching;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.transaction.annotation.EnableTransactionManagement;

/**
 * Short Link Application - High-concurrency URL shortening service.
 * Implements tiered caching (Bloom → Local → Redis → DB), ShardingSphere sharding,
 * Snowflake ID generation, and distributed synchronization via Redis Streams.
 */
@SpringBootApplication
@EnableCaching
@EnableScheduling
@EnableTransactionManagement
public class ShortLinkApplication {

    public static void main(String[] args) {
        SpringApplication.run(ShortLinkApplication.class, args);
    }
}
