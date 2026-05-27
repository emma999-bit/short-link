package com.shortlink.generator;

import com.shortlink.service.MachineIdService;
import com.shortlink.util.Base62Util;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.concurrent.atomic.AtomicLong;

/**
 * Short code generator based on Snowflake algorithm.
 * <p>
 * Bit layout (64-bit):
 * [1 sign][41 timestamp ms][10 machineId][12 sequence]
 * <p>
 * Clock-rollback handling:
 * - < 5ms: spin-wait for clock to catch up
 * - 5–20ms: use lastTimestamp to generate (accept slight duplication risk)
 * - > 20ms: switch to alternate clock (sequence-based monotonic increment)
 */
@Component
public class ShortCodeGenerator {

    private static final Logger log = LoggerFactory.getLogger(ShortCodeGenerator.class);

    // Epoch: 2024-01-01T00:00:00Z
    private static final long EPOCH = 1704067200000L;

    private static final int MACHINE_ID_BITS = 10;
    private static final int SEQUENCE_BITS = 12;

    private static final long MAX_MACHINE_ID = ~(-1L << MACHINE_ID_BITS); // 1023
    private static final long MAX_SEQUENCE = ~(-1L << SEQUENCE_BITS);     // 4095

    private static final int MACHINE_ID_SHIFT = SEQUENCE_BITS;             // 12
    private static final int TIMESTAMP_SHIFT = SEQUENCE_BITS + MACHINE_ID_BITS; // 22

    // Clock rollback thresholds (ms)
    private static final long SMALL_ROLLBACK_MS = 5;
    private static final long MEDIUM_ROLLBACK_MS = 20;

    @Value("${short-link.snowflake.code-length:8}")
    private int codeLength;

    private final MachineIdService machineIdService;

    private long lastTimestamp = -1L;
    private long sequence = 0L;

    // Alternate clock counter for severe rollback (> 20ms)
    private final AtomicLong altClockCounter = new AtomicLong(0);
    private volatile boolean useAltClock = false;
    private volatile long altClockBase = 0L;

    public ShortCodeGenerator(MachineIdService machineIdService) {
        this.machineIdService = machineIdService;
    }

    /**
     * Generates a unique short code using Snowflake algorithm with Base62 encoding.
     * Thread-safe via synchronized method.
     *
     * @return Base62-encoded short code of at least codeLength characters
     */
    public synchronized String generate() {
        long id = nextId();
        // Use only lower bits to fit in codeLength Base62 chars
        long maxVal = Base62Util.maxValue(codeLength);
        long encoded = id & maxVal; // mask to fit in codeLength
        return Base62Util.encode(encoded, codeLength);
    }

    /**
     * Generates the next raw Snowflake ID.
     */
    public synchronized long nextId() {
        long machineId = machineIdService.getMachineId() & MAX_MACHINE_ID;
        long timestamp = currentTimeMillis();

        long diff = lastTimestamp - timestamp;
        if (diff > 0) {
            // Clock rollback detected
            if (diff < SMALL_ROLLBACK_MS) {
                // Small rollback: wait for clock to catch up
                log.warn("Clock rollback detected diff={}ms, waiting", diff);
                timestamp = waitForNextMillis(lastTimestamp);
            } else if (diff < MEDIUM_ROLLBACK_MS) {
                // Medium rollback: use lastTimestamp
                log.warn("Clock rollback detected diff={}ms, using lastTimestamp", diff);
                timestamp = lastTimestamp;
            } else {
                // Severe rollback: switch to alternate clock
                log.error("Severe clock rollback detected diff={}ms, switching to alternate clock", diff);
                switchToAltClock(timestamp);
                timestamp = getAltClockTimestamp();
            }
        } else if (useAltClock) {
            // Recover from alt clock when real clock catches up
            long altTs = getAltClockTimestamp();
            if (timestamp > altTs) {
                log.info("Real clock caught up, reverting from alternate clock");
                useAltClock = false;
            } else {
                timestamp = altTs;
            }
        }

        if (timestamp == lastTimestamp) {
            sequence = (sequence + 1) & MAX_SEQUENCE;
            if (sequence == 0) {
                // Sequence overflow: wait for next millisecond
                timestamp = waitForNextMillis(lastTimestamp);
            }
        } else {
            sequence = 0L;
        }

        lastTimestamp = timestamp;

        return ((timestamp - EPOCH) << TIMESTAMP_SHIFT)
                | (machineId << MACHINE_ID_SHIFT)
                | sequence;
    }

    private long waitForNextMillis(long lastTs) {
        long ts = currentTimeMillis();
        int maxWait = 100; // spin max 100ms to avoid infinite loop
        while (ts <= lastTs && maxWait-- > 0) {
            Thread.onSpinWait();
            ts = currentTimeMillis();
        }
        return ts > lastTs ? ts : lastTs + 1;
    }

    private void switchToAltClock(long realTimestamp) {
        if (!useAltClock) {
            useAltClock = true;
            altClockBase = realTimestamp;
            altClockCounter.set(0);
            log.warn("Switched to alternate clock, altClockBase={}", altClockBase);
        }
    }

    private long getAltClockTimestamp() {
        // Alt clock: base + counter/4095 (simulate ms tick every 4096 IDs)
        return altClockBase + altClockCounter.getAndIncrement() / (MAX_SEQUENCE + 1);
    }

    private long currentTimeMillis() {
        return System.currentTimeMillis();
    }

    /**
     * Returns current generator status for monitoring.
     */
    public GeneratorStatus getStatus() {
        return new GeneratorStatus(
                machineIdService.getMachineId(),
                lastTimestamp,
                sequence,
                useAltClock
        );
    }

    /**
     * Immutable snapshot of generator state for debug/monitor APIs.
     */
    public record GeneratorStatus(int machineId, long lastTimestamp, long sequence, boolean usingAltClock) {}
}
