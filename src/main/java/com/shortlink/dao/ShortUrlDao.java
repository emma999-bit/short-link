package com.shortlink.dao;

import com.shortlink.entity.ShortUrlMapping;
import com.shortlink.service.ShardingStrategyService;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.time.LocalDateTime;
import java.util.Map;
import java.util.Optional;

/**
 * Data access object for ShortUrlMapping.
 * <p>
 * Uses JPA EntityManager for standard CRUD (sharded by ShardingSphere)
 * and raw DataSource for precise shard queries that bypass the ShardingSphere middleware.
 */
@Repository
public class ShortUrlDao {

    private static final Logger log = LoggerFactory.getLogger(ShortUrlDao.class);

    @PersistenceContext
    private EntityManager entityManager;

    @Autowired
    private ShardingStrategyService shardingStrategy;

    @Autowired
    @Qualifier("rawDataSourcePool")
    private Map<Integer, DataSource> rawDataSourcePool;

    /**
     * Finds a ShortUrlMapping by short code using JPA (ShardingSphere routes to correct shard).
     */
    public Optional<ShortUrlMapping> findByShortCode(String shortCode) {
        try {
            var result = entityManager.createQuery(
                "SELECT m FROM ShortUrlMapping m WHERE m.shortCode = :shortCode AND m.status = 1",
                ShortUrlMapping.class
            ).setParameter("shortCode", shortCode).getResultList();
            return result.isEmpty() ? Optional.empty() : Optional.of(result.get(0));
        } catch (Exception e) {
            log.error("Error finding by shortCode={}: {}", shortCode, e.getMessage(), e);
            return Optional.empty();
        }
    }

    /**
     * Finds a ShortUrlMapping by URL hash using JPA.
     */
    public Optional<ShortUrlMapping> findByUrlHash(String urlHash) {
        try {
            // This query is a broadcast query across shards since we don't have urlHash as shard key
            var result = entityManager.createQuery(
                "SELECT m FROM ShortUrlMapping m WHERE m.originUrlHash = :urlHash AND m.status = 1",
                ShortUrlMapping.class
            ).setParameter("urlHash", urlHash).getResultList();
            return result.isEmpty() ? Optional.empty() : Optional.of(result.get(0));
        } catch (Exception e) {
            log.error("Error finding by urlHash={}: {}", urlHash, e.getMessage(), e);
            return Optional.empty();
        }
    }

    /**
     * Performs a precise pre-check query on a specific shard, bypassing ShardingSphere.
     * Used before acquiring a distributed lock to reduce contention.
     *
     * @param shortCode  the short code to check
     * @return true if a record with this shortCode exists in the target shard
     */
    public boolean preCheckByShortCode(String shortCode) {
        int dbIndex = shardingStrategy.calculateDbIndex(shortCode);
        int tableIndex = shardingStrategy.calculateTableIndex(shortCode);
        String tableName = "short_url_mapping_" + tableIndex;

        DataSource ds = rawDataSourcePool.get(dbIndex);
        if (ds == null) {
            log.warn("No raw datasource for dbIndex={}, falling back to JPA check", dbIndex);
            return findByShortCode(shortCode).isPresent();
        }

        try (Connection conn = ds.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                 "SELECT COUNT(1) FROM " + tableName + " WHERE short_code = ? AND status = 1")) {
            ps.setString(1, shortCode);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() && rs.getInt(1) > 0;
            }
        } catch (Exception e) {
            log.warn("Pre-check query failed for shortCode={} db={} table={}: {}",
                    shortCode, dbIndex, tableName, e.getMessage());
            return false;
        }
    }

    /**
     * Saves a ShortUrlMapping entity.
     * Handles unique constraint violations with self-healing:
     * clears the EntityManager context and re-queries to merge with the existing record.
     */
    @Transactional
    public ShortUrlMapping save(ShortUrlMapping mapping) {
        try {
            entityManager.persist(mapping);
            entityManager.flush();
            return mapping;
        } catch (jakarta.persistence.PersistenceException e) {
            // Check for duplicate key / unique constraint violation
            Throwable cause = e.getCause();
            if (isDuplicateKeyException(e)) {
                log.warn("Duplicate key on save for shortCode={}, self-healing: clear + re-query",
                        mapping.getShortCode());
                // Clear stale EntityManager state
                entityManager.clear();
                // Re-query the existing record and return it
                return findByShortCode(mapping.getShortCode())
                        .orElseThrow(() -> new RuntimeException(
                                "Duplicate key but record not found for shortCode: " + mapping.getShortCode(), e));
            }
            throw e;
        }
    }

    /**
     * Increments the access count for a shortCode by delta.
     */
    @Transactional
    public void incrementAccessCount(String shortCode, long delta) {
        entityManager.createQuery(
            "UPDATE ShortUrlMapping m SET m.accessCount = m.accessCount + :delta, " +
            "m.updateTime = :now WHERE m.shortCode = :shortCode"
        )
        .setParameter("delta", delta)
        .setParameter("now", LocalDateTime.now())
        .setParameter("shortCode", shortCode)
        .executeUpdate();
    }

    /**
     * Soft-deletes a short URL mapping.
     */
    @Transactional
    public void softDelete(String shortCode) {
        entityManager.createQuery(
            "UPDATE ShortUrlMapping m SET m.status = 0, m.updateTime = :now WHERE m.shortCode = :shortCode"
        )
        .setParameter("now", LocalDateTime.now())
        .setParameter("shortCode", shortCode)
        .executeUpdate();
    }

    private boolean isDuplicateKeyException(Exception e) {
        String msg = e.getMessage();
        if (msg != null && (msg.contains("Duplicate entry") || msg.contains("duplicate key")
                || msg.contains("uk_short_code") || msg.contains("uk_url_hash"))) {
            return true;
        }
        Throwable cause = e.getCause();
        return cause != null && (cause instanceof java.sql.SQLIntegrityConstraintViolationException);
    }
}
