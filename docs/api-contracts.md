# API Contracts - kv-store

## Public KV API (`StorageEngine`)

The main entry point for the storage engine.

### `void put(byte[] key, byte[] value)`
Inserts or updates a key-value pair.
- **Durability:** Synchronously appended to WAL before acknowledgement.
- **Concurrency:** Thread-safe, coordinates with write backpressure.
- **Exceptions:** `StorageException` (Budget Exceeded, I/O Error, Engine Closed).

### `Optional<byte[]> get(byte[] key)`
Retrieves the value associated with a key.
- **Search Order:** MemTable → SSTables (via Sparse Index & Bloom Filter).
- **Exceptions:** `StorageException` (Checksum Mismatch, I/O Error, Engine Closed).

### `void delete(byte[] key)`
Deletes a key-value pair using a tombstone.
- **Persistence:** Appended to WAL and MemTable as a tombstone record.
- **Exceptions:** `StorageException` (Budget Exceeded, I/O Error, Engine Closed).

## Telemetry API (`EngineMetrics`)

Provides real-time observability into engine internals.

| Metric | Type | Description |
|--------|------|-------------|
| `getReadThroughputOpsPerSec()` | Counter | Sustained read operations per second. |
| `getWriteThroughputOpsPerSec()` | Counter | Sustained write operations per second. |
| `getReadLatencyP99Ms()` | Histogram | 99th percentile read latency in milliseconds. |
| `getWriteLatencyP99Ms()` | Histogram | 99th percentile write latency in milliseconds. |
| `getCacheHitRatio()` | Gauge | Normalized [0.0, 1.0] hit ratio of the block cache. |
| `getCompactionLagMs()` | Gauge | Current background maintenance lag. |
| `getHeapUsedBytes()` | Gauge | Tracked heap memory usage. |
| `getOffHeapUsedBytes()` | Gauge | Tracked off-heap (Mapped) memory usage. |

## Internal Management (`MetricsController`)

| Method | Description |
|--------|-------------|
| `enable()` | Enables metrics collection. |
| `disable()` | Disables metrics collection (minimal overhead). |
| `isEnabled()` | Checks current status. |
