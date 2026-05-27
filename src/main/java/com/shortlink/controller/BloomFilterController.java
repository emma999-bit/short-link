package com.shortlink.controller;

import com.shortlink.service.LocalBloomFilterService;
import com.shortlink.service.RedisTimeBasedBloomFilterService;
import com.shortlink.service.TieredBloomFilterService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.Map;

/**
 * Debug API for Bloom filter status inspection.
 * Provides observability into both local and Redis-based Bloom filter tiers.
 */
@RestController
@RequestMapping("/debug/bloom")
public class BloomFilterController {

    private final TieredBloomFilterService tieredBloom;
    private final LocalBloomFilterService localBloom;
    private final RedisTimeBasedBloomFilterService redisBloom;

    public BloomFilterController(TieredBloomFilterService tieredBloom,
                                 LocalBloomFilterService localBloom,
                                 RedisTimeBasedBloomFilterService redisBloom) {
        this.tieredBloom = tieredBloom;
        this.localBloom = localBloom;
        this.redisBloom = redisBloom;
    }

    /**
     * Returns the overall Bloom filter status.
     * GET /debug/bloom/status
     */
    @GetMapping("/status")
    public ResponseEntity<Map<String, Object>> getStatus() {
        Map<String, Object> status = new HashMap<>();

        // Local bloom stats
        Map<String, Object> local = new HashMap<>();
        local.put("approximateElementCount", localBloom.approximateElementCount());
        local.put("expectedFpp", localBloom.expectedFpp());
        status.put("local", local);

        // Redis bloom active slices
        Map<String, Object> redis = new HashMap<>();
        redis.put("activeSlices", redisBloom.getActiveSliceKeys());
        redis.put("currentSlice", redisBloom.currentSliceKey());
        status.put("redis", redis);

        // Tiered stats
        Map<String, Object> tiered = new HashMap<>();
        tiered.put("approximateElementCount", tieredBloom.approximateElementCount());
        tiered.put("expectedFpp", tieredBloom.expectedFpp());
        status.put("tiered", tiered);

        return ResponseEntity.ok(status);
    }

    /**
     * Tests whether a short code might be present.
     * GET /debug/bloom/check/{shortCode}
     */
    @GetMapping("/check/{shortCode}")
    public ResponseEntity<Map<String, Object>> check(@PathVariable String shortCode) {
        Map<String, Object> result = new HashMap<>();
        result.put("shortCode", shortCode);
        result.put("localMightContain", localBloom.mightContain(shortCode));
        result.put("redisMightContain", redisBloom.mightContain(shortCode));
        result.put("tieredMightContain", tieredBloom.mightContain(shortCode));
        return ResponseEntity.ok(result);
    }

    /**
     * Triggers a manual reset of the local Bloom filter.
     * POST /debug/bloom/reset
     */
    @PostMapping("/reset")
    public ResponseEntity<Map<String, String>> resetLocal() {
        tieredBloom.resetLocal();
        return ResponseEntity.ok(Map.of("status", "reset", "message", "Local bloom filter reset successfully"));
    }
}
