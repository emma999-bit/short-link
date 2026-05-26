package com.shortlink.entity;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * Short URL mapping entity. Represents a mapping between a short code and the original URL.
 * Sharded across multiple databases and tables via ShardingSphere using short_code as the sharding key.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Entity
@Table(name = "short_url_mapping",
        indexes = {
                @Index(name = "idx_create_time", columnList = "create_time")
        },
        uniqueConstraints = {
                @UniqueConstraint(name = "uk_short_code", columnNames = "short_code"),
                @UniqueConstraint(name = "uk_url_hash", columnNames = "origin_url_hash")
        })
public class ShortUrlMapping {

    @Id
    @Column(name = "id", nullable = false)
    private Long id;

    @Column(name = "short_code", nullable = false, length = 20)
    private String shortCode;

    @Column(name = "origin_url", nullable = false, columnDefinition = "TEXT")
    private String originUrl;

    @Column(name = "origin_url_hash", nullable = false, length = 64)
    private String originUrlHash;

    @Column(name = "create_time", nullable = false)
    private LocalDateTime createTime;

    @Column(name = "update_time", nullable = false)
    private LocalDateTime updateTime;

    @Column(name = "expire_days")
    private Integer expireDays;

    @Column(name = "access_count")
    @Builder.Default
    private Long accessCount = 0L;

    @Column(name = "status")
    @Builder.Default
    private Integer status = 1;

    @Column(name = "creator", length = 64)
    private String creator;

    @PrePersist
    public void prePersist() {
        LocalDateTime now = LocalDateTime.now();
        if (createTime == null) createTime = now;
        if (updateTime == null) updateTime = now;
    }

    @PreUpdate
    public void preUpdate() {
        updateTime = LocalDateTime.now();
    }
}
