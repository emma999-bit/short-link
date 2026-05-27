-- Short Link System Database Initialization Script
-- Creates databases and sharded tables for dev environment (2 DBs x 4 tables)
-- Production: 32 DBs x 256 tables

-- ===== Database: short_link_0 =====
CREATE DATABASE IF NOT EXISTS short_link_0
    CHARACTER SET utf8mb4
    COLLATE utf8mb4_unicode_ci;

USE short_link_0;

CREATE TABLE IF NOT EXISTS short_url_mapping_0 (
    id             BIGINT       NOT NULL,
    short_code     VARCHAR(20)  NOT NULL,
    origin_url     TEXT         NOT NULL,
    origin_url_hash VARCHAR(64) NOT NULL,
    create_time    DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    update_time    DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    expire_days    INT          DEFAULT NULL,
    access_count   BIGINT       DEFAULT 0,
    status         TINYINT      DEFAULT 1,
    creator        VARCHAR(64),
    PRIMARY KEY (id),
    UNIQUE KEY uk_short_code (short_code),
    UNIQUE KEY uk_url_hash (origin_url_hash),
    KEY idx_create_time (create_time)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS short_url_mapping_1 (
    id             BIGINT       NOT NULL,
    short_code     VARCHAR(20)  NOT NULL,
    origin_url     TEXT         NOT NULL,
    origin_url_hash VARCHAR(64) NOT NULL,
    create_time    DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    update_time    DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    expire_days    INT          DEFAULT NULL,
    access_count   BIGINT       DEFAULT 0,
    status         TINYINT      DEFAULT 1,
    creator        VARCHAR(64),
    PRIMARY KEY (id),
    UNIQUE KEY uk_short_code (short_code),
    UNIQUE KEY uk_url_hash (origin_url_hash),
    KEY idx_create_time (create_time)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS short_url_mapping_2 (
    id             BIGINT       NOT NULL,
    short_code     VARCHAR(20)  NOT NULL,
    origin_url     TEXT         NOT NULL,
    origin_url_hash VARCHAR(64) NOT NULL,
    create_time    DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    update_time    DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    expire_days    INT          DEFAULT NULL,
    access_count   BIGINT       DEFAULT 0,
    status         TINYINT      DEFAULT 1,
    creator        VARCHAR(64),
    PRIMARY KEY (id),
    UNIQUE KEY uk_short_code (short_code),
    UNIQUE KEY uk_url_hash (origin_url_hash),
    KEY idx_create_time (create_time)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS short_url_mapping_3 (
    id             BIGINT       NOT NULL,
    short_code     VARCHAR(20)  NOT NULL,
    origin_url     TEXT         NOT NULL,
    origin_url_hash VARCHAR(64) NOT NULL,
    create_time    DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    update_time    DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    expire_days    INT          DEFAULT NULL,
    access_count   BIGINT       DEFAULT 0,
    status         TINYINT      DEFAULT 1,
    creator        VARCHAR(64),
    PRIMARY KEY (id),
    UNIQUE KEY uk_short_code (short_code),
    UNIQUE KEY uk_url_hash (origin_url_hash),
    KEY idx_create_time (create_time)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- ===== Database: short_link_1 =====
CREATE DATABASE IF NOT EXISTS short_link_1
    CHARACTER SET utf8mb4
    COLLATE utf8mb4_unicode_ci;

USE short_link_1;

CREATE TABLE IF NOT EXISTS short_url_mapping_0 (
    id             BIGINT       NOT NULL,
    short_code     VARCHAR(20)  NOT NULL,
    origin_url     TEXT         NOT NULL,
    origin_url_hash VARCHAR(64) NOT NULL,
    create_time    DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    update_time    DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    expire_days    INT          DEFAULT NULL,
    access_count   BIGINT       DEFAULT 0,
    status         TINYINT      DEFAULT 1,
    creator        VARCHAR(64),
    PRIMARY KEY (id),
    UNIQUE KEY uk_short_code (short_code),
    UNIQUE KEY uk_url_hash (origin_url_hash),
    KEY idx_create_time (create_time)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS short_url_mapping_1 (
    id             BIGINT       NOT NULL,
    short_code     VARCHAR(20)  NOT NULL,
    origin_url     TEXT         NOT NULL,
    origin_url_hash VARCHAR(64) NOT NULL,
    create_time    DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    update_time    DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    expire_days    INT          DEFAULT NULL,
    access_count   BIGINT       DEFAULT 0,
    status         TINYINT      DEFAULT 1,
    creator        VARCHAR(64),
    PRIMARY KEY (id),
    UNIQUE KEY uk_short_code (short_code),
    UNIQUE KEY uk_url_hash (origin_url_hash),
    KEY idx_create_time (create_time)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS short_url_mapping_2 (
    id             BIGINT       NOT NULL,
    short_code     VARCHAR(20)  NOT NULL,
    origin_url     TEXT         NOT NULL,
    origin_url_hash VARCHAR(64) NOT NULL,
    create_time    DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    update_time    DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    expire_days    INT          DEFAULT NULL,
    access_count   BIGINT       DEFAULT 0,
    status         TINYINT      DEFAULT 1,
    creator        VARCHAR(64),
    PRIMARY KEY (id),
    UNIQUE KEY uk_short_code (short_code),
    UNIQUE KEY uk_url_hash (origin_url_hash),
    KEY idx_create_time (create_time)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS short_url_mapping_3 (
    id             BIGINT       NOT NULL,
    short_code     VARCHAR(20)  NOT NULL,
    origin_url     TEXT         NOT NULL,
    origin_url_hash VARCHAR(64) NOT NULL,
    create_time    DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    update_time    DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    expire_days    INT          DEFAULT NULL,
    access_count   BIGINT       DEFAULT 0,
    status         TINYINT      DEFAULT 1,
    creator        VARCHAR(64),
    PRIMARY KEY (id),
    UNIQUE KEY uk_short_code (short_code),
    UNIQUE KEY uk_url_hash (origin_url_hash),
    KEY idx_create_time (create_time)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
