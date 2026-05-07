package com.kvstore.telemetry;

/**
 * Immutable DTO representing a point-in-time snapshot of engine metrics.
 * <p>
 * This record captures a consistent view of the system's operational state,
 * including performance characteristics (throughput, latency) and resource 
 * utilization (memory). Being a record, it is immutable and thread-safe.
 * </p>
 *
 * @param readThroughputOpsPerSec  The number of read operations completed per second.
 * @param writeThroughputOpsPerSec The number of write operations completed per second.
 * @param readLatencyP50Ms        The 50th percentile (median) latency for read operations in milliseconds.
 * @param readLatencyP99Ms        The 99th percentile latency for read operations in milliseconds.
 * @param readLatencyP999Ms       The 99.9th percentile latency for read operations in milliseconds.
 * @param writeLatencyP50Ms       The 50th percentile (median) latency for write operations in milliseconds.
 * @param writeLatencyP99Ms       The 99th percentile latency for write operations in milliseconds.
 * @param writeLatencyP999Ms      The 99.9th percentile latency for write operations in milliseconds.
 * @param cacheHitRatio            The ratio of block cache hits to total cache requests (0.0 to 1.0).
 * @param compactionLagMs         The current delay in the compaction pipeline in milliseconds.
 * @param heapUsedBytes            The amount of JVM heap memory currently utilized by the engine.
 * @param offHeapUsedBytes         The amount of off-heap memory currently utilized by the engine (e.g., FFM allocations).
 * @param totalMemoryLimitBytes    The maximum configured memory limit for the engine.
 * @param checksumErrorCount       The cumulative number of checksum failures detected during SSTable reads.
 * @param budgetExceededCount      The cumulative number of times the memory budget allocation was rejected.
 */
public record MetricsSnapshot(
    long readThroughputOpsPerSec,
    long writeThroughputOpsPerSec,
    long readLatencyP50Ms,
    long readLatencyP99Ms,
    long readLatencyP999Ms,
    long writeLatencyP50Ms,
    long writeLatencyP99Ms,
    long writeLatencyP999Ms,
    double cacheHitRatio,
    long compactionLagMs,
    long heapUsedBytes,
    long offHeapUsedBytes,
    long totalMemoryLimitBytes,
    long checksumErrorCount,
    long budgetExceededCount
) {}
