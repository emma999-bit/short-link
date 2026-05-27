package com.shortlink.config;

import com.zaxxer.hikari.HikariDataSource;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import javax.sql.DataSource;
import java.util.HashMap;
import java.util.Map;

/**
 * Raw (non-sharded) data source pool configuration.
 * Bypasses ShardingSphere to allow direct queries to specific shards.
 * Used by ShortUrlDao for precise precheck queries.
 */
@Configuration
public class RawDataSourceConfig {

    @Value("${short-link.raw-datasource.ds0-url:jdbc:mysql://localhost:3306/short_link_0?useUnicode=true&characterEncoding=UTF-8&useSSL=false&serverTimezone=Asia/Shanghai&allowPublicKeyRetrieval=true}")
    private String ds0Url;

    @Value("${short-link.raw-datasource.ds1-url:jdbc:mysql://localhost:3307/short_link_1?useUnicode=true&characterEncoding=UTF-8&useSSL=false&serverTimezone=Asia/Shanghai&allowPublicKeyRetrieval=true}")
    private String ds1Url;

    @Value("${short-link.raw-datasource.username:root}")
    private String username;

    @Value("${short-link.raw-datasource.password:root123}")
    private String password;

    @Value("${short-link.sharding.database-count:2}")
    private int databaseCount;

    /**
     * Returns a map of dsIndex → DataSource for raw (bypass-sharding) queries.
     * Each DataSource is a dedicated HikariCP pool for that shard.
     */
    @Bean("rawDataSourcePool")
    public Map<Integer, DataSource> rawDataSourcePool() {
        Map<Integer, DataSource> pool = new HashMap<>();
        String[] urls = buildUrls();
        for (int i = 0; i < databaseCount; i++) {
            HikariDataSource ds = new HikariDataSource();
            ds.setDriverClassName("com.mysql.cj.jdbc.Driver");
            ds.setJdbcUrl(urls[i]);
            ds.setUsername(username);
            ds.setPassword(password);
            ds.setMaximumPoolSize(10);
            ds.setMinimumIdle(2);
            ds.setConnectionTimeout(5000);
            ds.setIdleTimeout(300000);
            ds.setPoolName("raw-ds-" + i);
            pool.put(i, ds);
        }
        return pool;
    }

    private String[] buildUrls() {
        // For dev: 2 DBs. Build URL array based on databaseCount.
        String[] urls = new String[databaseCount];
        // Default dev environment: ds_0 on 3306, ds_1 on 3307
        if (databaseCount >= 1) urls[0] = ds0Url;
        if (databaseCount >= 2) urls[1] = ds1Url;
        // For more DBs, generate dynamically (production scenario)
        for (int i = 2; i < databaseCount; i++) {
            urls[i] = String.format(
                "jdbc:mysql://localhost:%d/short_link_%d?useUnicode=true&characterEncoding=UTF-8" +
                "&useSSL=false&serverTimezone=Asia/Shanghai&allowPublicKeyRetrieval=true",
                3306 + i, i);
        }
        return urls;
    }
}
