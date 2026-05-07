package com.kvstore.telemetry;

import java.util.Locale;

/**
 * Formatter for engine metrics snapshots.
 * <p>
 * This class provides a human-readable representation of a {@link MetricsSnapshot},
 * including throughput, latency percentiles, cache efficiency, and memory usage.
 * It is designed for logging or console output.
 * </p>
 * <p>
 * Memory values are automatically scaled to the most appropriate unit (B, KB, MB, GB, etc.)
 * using a logarithmic scale calculation to ensure readability for humans.
 * </p>
 */
public class MetricsFormatter {

    /**
     * Formats the given metrics snapshot into a multi-line human-readable string.
     * <p>
     * The resulting string follows a structured format with indented sections
     * for different metric categories.
     * </p>
     *
     * @param snapshot The point-in-time snapshot of engine metrics to format. Must not be null.
     * @return A formatted multi-line string representation of the engine metrics.
     */
    public String format(MetricsSnapshot snapshot) {
        return String.format(Locale.US,
            "Engine Metrics Snapshot:\n" +
            "  Throughput: Read: %d ops/sec, Write: %d ops/sec\n" +
            "  Latency Read: P50: %dms, P99: %dms, P99.9: %dms\n" +
            "  Latency Write: P50: %dms, P99: %dms, P99.9: %dms\n" +
            "  Cache Hit Ratio: %.2f%%\n" +
            "  Compaction Lag: %dms\n" +
            "  Memory: Heap: %s, Off-Heap: %s, Limit: %s\n" +
            "  Errors: Checksum: %d, Budget Exceeded: %d",
            snapshot.readThroughputOpsPerSec(),
            snapshot.writeThroughputOpsPerSec(),
            snapshot.readLatencyP50Ms(),
            snapshot.readLatencyP99Ms(),
            snapshot.readLatencyP999Ms(),
            snapshot.writeLatencyP50Ms(),
            snapshot.writeLatencyP99Ms(),
            snapshot.writeLatencyP999Ms(),
            snapshot.cacheHitRatio() * 100.0,
            snapshot.compactionLagMs(),
            formatBytes(snapshot.heapUsedBytes()),
            formatBytes(snapshot.offHeapUsedBytes()),
            formatBytes(snapshot.totalMemoryLimitBytes()),
            snapshot.checksumErrorCount(),
            snapshot.budgetExceededCount()
        );
    }

    /**
     * Formats a raw byte count into a human-readable string with appropriate SI units.
     * <p>
     * Logic: Calculates the exponent of 1024 for the given byte count and maps it
     * to the corresponding prefix (K, M, G, etc.).
     * </p>
     *
     * @param bytes The number of bytes to format. Must be non-negative.
     * @return A string representation with units (e.g., "1.25 MB").
     */
    private String formatBytes(long bytes) {
        if (bytes < 1024) return bytes + " B";
        // Calculate the power of 1024.
        int exp = (int) (Math.log(bytes) / Math.log(1024));
        // Pick the unit character based on the exponent.
        char pre = "KMGTPE".charAt(exp - 1);
        return String.format(Locale.US, "%.2f %cB", bytes / Math.pow(1024, exp), pre);
    }
}
