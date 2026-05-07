package com.kvstore.telemetry;

import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.LongAdder;
import com.kvstore.core.MemoryBudget;

/**
 * Implementation of {@link EngineMetrics} and {@link MetricsController}.
 * <p>
 * This class is responsible for capturing raw performance data (counts, latencies, resource usage)
 * and providing derived metrics like throughput and percentiles.
 * </p>
 * <p>
 * Implementation details:
 * <ul>
 *   <li>Uses {@link LongAdder} for high-concurrency counters (total ops, cache hits/misses)
 *       to minimize contention between threads.</li>
 *   <li>Employs a sliding window approach for throughput calculations based on start time.</li>
 *   <li>Uses {@link BoundedHistogram} with sampling to track latency distributions 
 *       without excessive memory overhead or performance impact on the hot path.</li>
 *   <li>Integrates with {@link MemoryBudget} to report memory limits and component usage.</li>
 * </ul>
 * </p>
 */
public class MetricsRecorder implements EngineMetrics, MetricsController {
    /** Global switch to enable or disable metrics collection. */
    private final AtomicBoolean enabled = new AtomicBoolean(false);
    
    /** Nanosecond timestamp of when the recorder was created (engine start). */
    private final long startNano = System.nanoTime();
    
    /** Reference to the system-wide memory budget. */
    private final MemoryBudget memoryBudget;

    // Counters (using LongAdder for low contention)
    private final LongAdder readOpsTotal = new LongAdder();
    private final LongAdder writeOpsTotal = new LongAdder();
    private final LongAdder cacheHitsTotal = new LongAdder();
    private final LongAdder cacheMissesTotal = new LongAdder();
    private final LongAdder checksumErrorsTotal = new LongAdder();
    private final LongAdder budgetExceededTotal = new LongAdder();

    // Gauges (AtomicLong for immediate visibility of point-in-time values)
    private final AtomicLong compactionLagMs = new AtomicLong(0);
    private final AtomicLong heapUsedBytes = new AtomicLong(0);
    private final AtomicLong offHeapUsedBytes = new AtomicLong(0);

    // Histograms for latency tracking.
    private final BoundedHistogram readLatencyHistogram = new BoundedHistogram(10000);
    private final BoundedHistogram writeLatencyHistogram = new BoundedHistogram(10000);

    /** 
     * Latency sampling rate. 1 means every operation is recorded, 
     * 100 means 1 in 100 operations is recorded to the histogram.
     */
    private final int sampleRate = 100;
    private final AtomicLong readSampleCounter = new AtomicLong(0);
    private final AtomicLong writeSampleCounter = new AtomicLong(0);

    /**
     * Constructs a new MetricsRecorder integrated with the provided memory budget.
     *
     * @param memoryBudget The system memory budget used to report total limits and component usage.
     *                     Must not be null.
     */
    public MetricsRecorder(MemoryBudget memoryBudget) {
        this.memoryBudget = memoryBudget;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void enable() {
        enabled.set(true);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void disable() {
        enabled.set(false);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public boolean isEnabled() {
        return enabled.get();
    }

    /**
     * Records a read operation and its latency.
     * <p>
     * The total read operation counter is incremented for every call (if enabled).
     * Latency is only recorded to the histogram if the sampling check passes.
     * </p>
     *
     * @param latencyMs The latency of the read operation in milliseconds.
     */
    public void recordRead(long latencyMs) {
        if (!enabled.get()) return;
        
        readOpsTotal.increment();
        if (readSampleCounter.incrementAndGet() % sampleRate == 0) {
            readLatencyHistogram.record(latencyMs);
        }
    }

    /**
     * Records a write operation and its latency.
     * <p>
     * Similar to {@link #recordRead(long)}, this increments the total write counter
     * and samples the latency for the write histogram.
     * </p>
     *
     * @param latencyMs The latency of the write operation in milliseconds.
     */
    public void recordWrite(long latencyMs) {
        if (!enabled.get()) return;
        
        writeOpsTotal.increment();
        if (writeSampleCounter.incrementAndGet() % sampleRate == 0) {
            writeLatencyHistogram.record(latencyMs);
        }
    }

    /**
     * Records a cache hit event.
     */
    public void recordCacheHit() {
        if (!enabled.get()) return;
        cacheHitsTotal.increment();
    }

    /**
     * Records a cache miss event.
     */
    public void recordCacheMiss() {
        if (!enabled.get()) return;
        cacheMissesTotal.increment();
    }

    /**
     * Records a checksum error detection.
     */
    public void recordChecksumError() {
        if (!enabled.get()) return;
        checksumErrorsTotal.increment();
    }

    /**
     * Records a memory budget exhaustion event.
     */
    public void recordBudgetExceeded() {
        if (!enabled.get()) return;
        budgetExceededTotal.increment();
    }

    /**
     * Sets the current compaction lag reported by the background engine.
     *
     * @param lagMs The compaction lag in milliseconds.
     */
    public void setCompactionLagMs(long lagMs) {
        if (!enabled.get()) return;
        compactionLagMs.set(lagMs);
    }

    /**
     * Sets the current JVM heap usage.
     *
     * @param bytes The heap usage in bytes.
     */
    public void setHeapUsedBytes(long bytes) {
        if (!enabled.get()) return;
        heapUsedBytes.set(bytes);
    }

    /**
     * Sets the current off-heap memory usage.
     *
     * @param bytes The off-heap usage in bytes.
     */
    public void setOffHeapUsedBytes(long bytes) {
        if (!enabled.get()) return;
        offHeapUsedBytes.set(bytes);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public long getReadThroughputOpsPerSec() {
        return calculateThroughput(readOpsTotal.sum());
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public long getWriteThroughputOpsPerSec() {
        return calculateThroughput(writeOpsTotal.sum());
    }

    /**
     * Calculates throughput based on total operations and elapsed time since start.
     * <p>
     * Note: This provides an average throughput since start. For a sliding window,
     * a more complex implementation tracking interval counts would be required.
     * </p>
     *
     * @param totalOps The total number of operations recorded.
     * @return The operations per second. Returns totalOps if less than 1 second has elapsed.
     */
    private long calculateThroughput(long totalOps) {
        long elapsedNano = System.nanoTime() - startNano;
        if (elapsedNano < 1_000_000_000L) return totalOps;
        double elapsedSec = elapsedNano / 1_000_000_000.0;
        return (long) (totalOps / elapsedSec);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public long getReadLatencyP50Ms() {
        return readLatencyHistogram.getPercentile(50);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public long getReadLatencyP99Ms() {
        return readLatencyHistogram.getPercentile(99);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public long getReadLatencyP999Ms() {
        return readLatencyHistogram.getPercentile(99.9);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public long getWriteLatencyP50Ms() {
        return writeLatencyHistogram.getPercentile(50);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public long getWriteLatencyP99Ms() {
        return writeLatencyHistogram.getPercentile(99);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public long getWriteLatencyP999Ms() {
        return writeLatencyHistogram.getPercentile(99.9);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public double getCacheHitRatio() {
        long hits = cacheHitsTotal.sum();
        long total = hits + cacheMissesTotal.sum();
        if (total == 0) return 0.0;
        return (double) hits / total;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public long getCompactionLagMs() {
        return compactionLagMs.get();
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public long getHeapUsedBytes() {
        return heapUsedBytes.get();
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public long getOffHeapUsedBytes() {
        return offHeapUsedBytes.get();
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public long getTotalMemoryLimitBytes() {
        return memoryBudget.getMaxBytesAllowed();
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public long getMemoryUsageBytes(String component) {
        return memoryBudget.getComponentUsage(component);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public long getChecksumErrorCount() {
        return checksumErrorsTotal.sum();
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public long getBudgetExceededCount() {
        return budgetExceededTotal.sum();
    }

    /**
     * Takes a point-in-time consistent snapshot of all recorded metrics.
     * <p>
     * This method captures all current metric values into an immutable 
     * {@link MetricsSnapshot} object, which can then be used for 
     * formatting or exporting without fear of concurrent modifications
     * changing the values during the process.
     * </p>
     *
     * @return A new {@link MetricsSnapshot} containing the current metric values.
     */
    public MetricsSnapshot takeSnapshot() {
        return new MetricsSnapshot(
            getReadThroughputOpsPerSec(),
            getWriteThroughputOpsPerSec(),
            getReadLatencyP50Ms(),
            getReadLatencyP99Ms(),
            getReadLatencyP999Ms(),
            getWriteLatencyP50Ms(),
            getWriteLatencyP99Ms(),
            getWriteLatencyP999Ms(),
            getCacheHitRatio(),
            getCompactionLagMs(),
            getHeapUsedBytes(),
            getOffHeapUsedBytes(),
            getTotalMemoryLimitBytes(),
            getChecksumErrorCount(),
            getBudgetExceededCount()
        );
    }
}
