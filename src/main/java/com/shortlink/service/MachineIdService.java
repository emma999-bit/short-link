package com.shortlink.service;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import org.redisson.api.RMap;
import org.redisson.api.RedissonClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.lang.management.ManagementFactory;
import java.net.InetAddress;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;

/**
 * Machine ID allocation service.
 * Automatically assigns a unique machine ID [0, maxMachineId] via Redis Map with heartbeat lease.
 * On JVM shutdown, releases the machine ID back to the pool.
 */
@Service
public class MachineIdService {

    private static final Logger log = LoggerFactory.getLogger(MachineIdService.class);

    @Value("${short-link.machine.redis-key:machine:id:registry}")
    private String registryKey;

    @Value("${short-link.machine.lease-seconds:30}")
    private long leaseSeconds;

    @Value("${short-link.machine.max-machine-id:1023}")
    private int maxMachineId;

    private final RedissonClient redisson;

    private volatile int machineId = -1;
    private volatile String nodeIdentifier;

    public MachineIdService(RedissonClient redisson) {
        this.redisson = redisson;
    }

    @PostConstruct
    public void init() {
        this.nodeIdentifier = buildNodeIdentifier();
        this.machineId = allocateMachineId();
        log.info("Allocated machineId={} for node={}", machineId, nodeIdentifier);
    }

    /**
     * Returns the allocated machine ID. Must be called after init.
     */
    public int getMachineId() {
        if (machineId < 0) {
            throw new IllegalStateException("Machine ID not yet allocated");
        }
        return machineId;
    }

    /**
     * Heartbeat: renew the lease every leaseSeconds/2.
     */
    @Scheduled(fixedDelayString = "#{${short-link.machine.lease-seconds:30} * 500}")
    public void renewLease() {
        if (machineId < 0) return;
        try {
            RMap<String, String> registry = redisson.getMap(registryKey);
            registry.put(String.valueOf(machineId), nodeIdentifier);
            log.debug("Renewed lease for machineId={}", machineId);
        } catch (Exception e) {
            log.warn("Failed to renew machine ID lease for machineId={}: {}", machineId, e.getMessage());
        }
    }

    @PreDestroy
    public void releaseMachineId() {
        if (machineId < 0) return;
        try {
            RMap<String, String> registry = redisson.getMap(registryKey);
            String current = registry.get(String.valueOf(machineId));
            if (nodeIdentifier.equals(current)) {
                registry.remove(String.valueOf(machineId));
                log.info("Released machineId={} for node={}", machineId, nodeIdentifier);
            }
        } catch (Exception e) {
            log.warn("Failed to release machineId={}: {}", machineId, e.getMessage());
        }
    }

    private int allocateMachineId() {
        RMap<String, String> registry = redisson.getMap(registryKey);
        // Try to find an unoccupied slot
        for (int attempt = 0; attempt <= maxMachineId; attempt++) {
            int candidate = ThreadLocalRandom.current().nextInt(0, maxMachineId + 1);
            String existing = registry.putIfAbsent(String.valueOf(candidate), nodeIdentifier);
            if (existing == null) {
                return candidate;
            }
        }
        // Sequential fallback
        for (int candidate = 0; candidate <= maxMachineId; candidate++) {
            String existing = registry.putIfAbsent(String.valueOf(candidate), nodeIdentifier);
            if (existing == null) {
                return candidate;
            }
        }
        // Fallback: use hash of nodeIdentifier
        log.warn("Could not allocate machineId from registry, using hash fallback");
        return Math.abs(nodeIdentifier.hashCode()) % (maxMachineId + 1);
    }

    private String buildNodeIdentifier() {
        String pid = ManagementFactory.getRuntimeMXBean().getName();
        String hostname;
        try {
            hostname = InetAddress.getLocalHost().getHostName();
        } catch (Exception e) {
            hostname = "unknown";
        }
        return hostname + ":" + pid + ":" + System.currentTimeMillis();
    }
}
