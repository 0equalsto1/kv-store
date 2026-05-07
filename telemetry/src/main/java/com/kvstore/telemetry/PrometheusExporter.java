package com.kvstore.telemetry;

import java.util.Locale;

/**
 * Exporter to format engine metrics in Prometheus exposition format.
 * <p>
 * This class translates a {@link MetricsSnapshot} into the plain-text format
 * expected by Prometheus scraping endpoints. It includes mandatory metadata
 * like HELP and TYPE for each exported metric.
 * </p>
 * <p>
 * Metrics are prefixed with {@code kvstore_} to avoid collisions and provide
 * a clear namespace in monitoring dashboards. Both counters and gauges are supported.
 * </p>
 */
public class PrometheusExporter {

    /**
     * Formats the given metrics snapshot into Prometheus exposition format.
     * <p>
     * Every metric in the snapshot is appended to the result with its
     * corresponding Prometheus metadata.
     * </p>
     *
     * @param snapshot The point-in-time snapshot of engine metrics to export. Must not be null.
     * @return A string containing all metrics in Prometheus format, ready for HTTP response.
     */
    public String export(MetricsSnapshot snapshot) {
        StringBuilder sb = new StringBuilder();
        
        appendMetric(sb, "kvstore_read_throughput_ops_per_sec", "Current read operations per second", "gauge", snapshot.readThroughputOpsPerSec());
        appendMetric(sb, "kvstore_write_throughput_ops_per_sec", "Current write operations per second", "gauge", snapshot.writeThroughputOpsPerSec());
        
        appendMetric(sb, "kvstore_read_latency_p50_ms", "Read latency P50 in milliseconds", "gauge", snapshot.readLatencyP50Ms());
        appendMetric(sb, "kvstore_read_latency_p99_ms", "Read latency P99 in milliseconds", "gauge", snapshot.readLatencyP99Ms());
        appendMetric(sb, "kvstore_read_latency_p999_ms", "Read latency P99.9 in milliseconds", "gauge", snapshot.readLatencyP999Ms());
        
        appendMetric(sb, "kvstore_write_latency_p50_ms", "Write latency P50 in milliseconds", "gauge", snapshot.writeLatencyP50Ms());
        appendMetric(sb, "kvstore_write_latency_p99_ms", "Write latency P99 in milliseconds", "gauge", snapshot.writeLatencyP99Ms());
        appendMetric(sb, "kvstore_write_latency_p999_ms", "Write latency P99.9 in milliseconds", "gauge", snapshot.writeLatencyP999Ms());
        
        appendMetric(sb, "kvstore_cache_hit_ratio", "Cache hit ratio (0.0 to 1.0)", "gauge", snapshot.cacheHitRatio());
        appendMetric(sb, "kvstore_compaction_lag_ms", "Compaction lag in milliseconds", "gauge", snapshot.compactionLagMs());
        
        appendMetric(sb, "kvstore_heap_used_bytes", "Memory used by engine on heap in bytes", "gauge", snapshot.heapUsedBytes());
        appendMetric(sb, "kvstore_off_heap_used_bytes", "Memory used by engine off-heap in bytes", "gauge", snapshot.offHeapUsedBytes());
        appendMetric(sb, "kvstore_total_memory_limit_bytes", "Total configured memory limit for engine in bytes", "gauge", snapshot.totalMemoryLimitBytes());
        
        appendMetric(sb, "kvstore_checksum_errors_total", "Total number of checksum mismatches detected", "counter", snapshot.checksumErrorCount());
        appendMetric(sb, "kvstore_budget_exceeded_total", "Total number of memory budget exceeded events", "counter", snapshot.budgetExceededCount());
        
        return sb.toString();
    }

    /**
     * Appends a single metric in Prometheus format to the StringBuilder.
     * <p>
     * Format:
     * {@code # HELP <name> <help>}
     * {@code # TYPE <name> <type>}
     * {@code <name> <value>}
     * </p>
     *
     * @param sb    The StringBuilder to append to. Must not be null.
     * @param name  The name of the metric (prefixed with kvstore_).
     * @param help  A brief description of what the metric measures.
     * @param type  The Prometheus metric type (e.g., "gauge", "counter").
     * @param value The current value of the metric.
     */
    private void appendMetric(StringBuilder sb, String name, String help, String type, Object value) {
        sb.append("# HELP ").append(name).append(" ").append(help).append("\n");
        sb.append("# TYPE ").append(name).append(" ").append(type).append("\n");
        // Use Locale.US to ensure dot as decimal separator.
        sb.append(name).append(" ").append(String.format(Locale.US, "%s", value)).append("\n");
    }
}
