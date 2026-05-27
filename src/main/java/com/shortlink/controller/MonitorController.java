package com.shortlink.controller;

import com.shortlink.generator.ShortCodeGenerator;
import com.shortlink.service.CacheSyncMonitorService;
import com.shortlink.service.ClockSyncMonitorService;
import com.shortlink.service.MachineIdService;
import com.shortlink.service.ShardingStrategyService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;

/**
 * Debug / observability API for system internals.
 * Exposes: generator state, clock drift, cache stats, sharding info.
 */
@RestController
@RequestMapping("/debug")
public class MonitorController {

    private final ShortCodeGenerator generator;
    private final MachineIdService machineIdService;
    private final ClockSyncMonitorService clockSync;
    private final CacheSyncMonitorService cacheSync;
    private final ShardingStrategyService sharding;

    public MonitorController(ShortCodeGenerator generator,
                             MachineIdService machineIdService,
                             ClockSyncMonitorService clockSync,
                             CacheSyncMonitorService cacheSync,
                             ShardingStrategyService sharding) {
        this.generator = generator;
        this.machineIdService = machineIdService;
        this.clockSync = clockSync;
        this.cacheSync = cacheSync;
        this.sharding = sharding;
    }

    /**
     * Returns Snowflake generator status.
     * GET /debug/generator
     */
    @GetMapping("/generator")
    public ResponseEntity<Map<String, Object>> getGeneratorStatus() {
        ShortCodeGenerator.GeneratorStatus status = generator.getStatus();
        Map<String, Object> result = new HashMap<>();
        result.put("machineId", status.machineId());
        result.put("lastTimestamp", status.lastTimestamp());
        result.put("lastTimestampIso", Instant.ofEpochMilli(status.lastTimestamp()).toString());
        result.put("sequence", status.sequence());
        result.put("usingAltClock", status.usingAltClock());
        result.put("allocatedMachineId", machineIdService.getMachineId());
        return ResponseEntity.ok(result);
    }

    /**
     * Returns clock synchronization status (JVM vs Redis drift).
     * GET /debug/clock
     */
    @GetMapping("/clock")
    public ResponseEntity<Map<String, Object>> getClockStatus() {
        ClockSyncMonitorService.ClockSyncStatus status = clockSync.getStatus();
        Map<String, Object> result = new HashMap<>();
        result.put("driftMs", status.driftMs());
        result.put("alert", status.alert());
        result.put("thresholdMs", status.thresholdMs());
        result.put("jvmTimeMs", System.currentTimeMillis());
        result.put("jvmTimeIso", Instant.now().toString());
        return ResponseEntity.ok(result);
    }

    /**
     * Returns local cache statistics.
     * GET /debug/cache
     */
    @GetMapping("/cache")
    public ResponseEntity<Map<String, Object>> getCacheStatus() {
        return ResponseEntity.ok(cacheSync.getLastStats());
    }

    /**
     * Returns sharding info for a given shortCode.
     * GET /debug/sharding/{shortCode}
     */
    @GetMapping("/sharding/{shortCode}")
    public ResponseEntity<Map<String, Object>> getShardingInfo(@PathVariable String shortCode) {
        Map<String, Object> result = new HashMap<>();
        result.put("shortCode", shortCode);
        result.put("dbIndex", sharding.calculateDbIndex(shortCode));
        result.put("tableIndex", sharding.calculateTableIndex(shortCode));
        result.put("redisSlot", sharding.calculateSlot("url:{" + shortCode + "}"));
        result.put("hashTagKey", sharding.buildHashTagKey("url", shortCode));
        result.put("databaseCount", sharding.getDatabaseCount());
        result.put("tableCount", sharding.getTableCount());
        return ResponseEntity.ok(result);
    }

    /**
     * Health check endpoint.
     * GET /debug/health
     */
    @GetMapping("/health")
    public ResponseEntity<Map<String, String>> health() {
        return ResponseEntity.ok(Map.of(
                "status", "UP",
                "machineId", String.valueOf(machineIdService.getMachineId()),
                "timestamp", Instant.now().toString()
        ));
    }
}
