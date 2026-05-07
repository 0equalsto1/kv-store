package com.kvstore.telemetry;

/**
 * Read-only interface for engine metrics following Pattern 7 naming conventions.
 * <p>
 * This interface provides access to throughput counters, latency histograms,
 * resource gauges, and error counters for monitoring the health and performance
 * of the LSM-tree storage engine.
 * </p>
 * <p>
 * The metrics exposed here are critical for observability, allowing operators
 * to detect performance bottlenecks, resource exhaustion, or data corruption
 * in real-time.
 * </p>
 */
public interface EngineMetrics {
    /**
     * Returns the current read throughput in operations per second.
     * <p>
     * Calculated since the engine start or over a sliding window, depending
     * on the implementation.
     * </p>
     * 
     * @return Read operations per second.
     */
    long getReadThroughputOpsPerSec();

    /**
     * Returns the current write throughput in operations per second.
     * <p>
     * Calculated since the engine start or over a sliding window, depending
     * on the implementation.
     * </p>
     * 
     * @return Write operations per second.
     */
    long getWriteThroughputOpsPerSec();
    
    /**
     * Returns the 50th percentile (median) latency for read operations.
     * 
     * @return Read latency P50 in milliseconds.
     */
    long getReadLatencyP50Ms();

    /**
     * Returns the 99th percentile latency for read operations.
     * 
     * @return Read latency P99 in milliseconds.
     */
    long getReadLatencyP99Ms();

    /**
     * Returns the 99.9th percentile latency for read operations.
     * 
     * @return Read latency P99.9 in milliseconds.
     */
    long getReadLatencyP999Ms();
    
    /**
     * Returns the 50th percentile (median) latency for write operations.
     * 
     * @return Write latency P50 in milliseconds.
     */
    long getWriteLatencyP50Ms();

    /**
     * Returns the 99th percentile latency for write operations.
     * 
     * @return Write latency P99 in milliseconds.
     */
    long getWriteLatencyP99Ms();

    /**
     * Returns the 99.9th percentile latency for write operations.
     * 
     * @return Write latency P99.9 in milliseconds.
     */
    long getWriteLatencyP999Ms();
    
    /**
     * Returns the block cache hit ratio.
     * 
     * @return A value between 0.0 and 1.0 representing the ratio of hits to total requests.
     */
    double getCacheHitRatio();

    /**
     * Returns the current compaction lag.
     * <p>
     * Compaction lag is the time difference between the newest SSTable and the oldest 
     * pending compaction task, or the total time a compaction has been pending.
     * High lag triggers write backpressure.
     * </p>
     * 
     * @return Compaction lag in milliseconds.
     */
    long getCompactionLagMs();

    /**
     * Returns the amount of JVM heap memory used by the engine.
     * 
     * @return Heap usage in bytes.
     */
    long getHeapUsedBytes();

    /**
     * Returns the amount of off-heap memory used by the engine.
     * <p>
     * This includes memory allocated via FFM API for SSTable segments and Block Cache.
     * </p>
     * 
     * @return Off-heap usage in bytes.
     */
    long getOffHeapUsedBytes();

    /**
     * Returns the total memory limit configured for the engine.
     * 
     * @return Total memory limit in bytes.
     */
    long getTotalMemoryLimitBytes();

    /**
     * Returns the memory usage for a specific engine component.
     * 
     * @param component The name of the component (e.g., "memtable", "cache", "wal").
     * @return Component usage in bytes.
     */
    long getMemoryUsageBytes(String component);
    
    /**
     * Returns the total count of checksum errors detected since engine start.
     * 
     * @return Checksum error count.
     */
    long getChecksumErrorCount();

    /**
     * Returns the total count of memory budget exceeded events since engine start.
     * 
     * @return Budget exceeded count.
     */
    long getBudgetExceededCount();
}
