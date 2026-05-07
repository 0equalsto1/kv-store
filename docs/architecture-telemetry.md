# Architecture - Telemetry Module

The `telemetry` module provides comprehensive observability into the performance and health of the `kv-store`.

## Key Components

### `MetricsRecorder`
The central point for gathering system-wide metrics.
- **Lock-Free:** Uses `LongAdder` and `AtomicLong` for minimal overhead in high-concurrency scenarios.
- **Sampling:** Percentile metrics (P99, etc.) are calculated using a `BoundedHistogram` with a configurable sample rate (e.g., 1/100 operations).

### `EngineMetrics` (Interface)
Exposes the gathered metrics to external consumers, including:
- Read/Write throughput (OPS).
- Read/Write latency percentiles (P50, P99, P99.9).
- Cache hit/miss ratios.
- Memory usage (heap vs. off-heap).
- System errors (checksum failures, budget exceeded).

### `PrometheusExporter`
(Optional) Formats the internal metrics for scraping by Prometheus-compatible monitoring systems.

## Design Goals

- **Low Overhead:** Target <1% total CPU utilization for the entire telemetry subsystem.
- **Accuracy:** Provides precise tail-latency insights essential for production-grade systems.
- **Isolation:** Metrics collection is isolated from primary data paths to prevent maintenance tasks from affecting user latency.
