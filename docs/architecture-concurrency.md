# Architecture - Concurrency Module

The `concurrency` module manages the thread-safe coordination of all engine operations, optimized for high throughput using modern Java features.

## Key Components

### `WriteBackpressure`
A coordination primitive that protects the system from maintenance starvation.
- **Lag Detection:** Monitors the compaction lag reported by the `CompactionScheduler`.
- **Throttling:** If lag exceeds the `backpressureLagThresholdMillis` (default 30s), new write operations are paused until compaction catches up.

### `BackpressureSignal` (Interface)
Defines the contract for checking capacity (`canWrite`) and parking threads (`waitForCapacity`).

## Modern Java Features

### Virtual Threads (Project Loom)
The `kv-store` is designed to be called from thousands of concurrent virtual threads.
- **Efficient Parking:** When a write is blocked by backpressure, the virtual thread is unmounted from its carrier thread, freeing up CPU resources for other tasks.
- **I/O Optimization:** Synchronous-style I/O operations on memory segments don't pin the carrier thread, allowing for massive concurrency.

## Threading Model

- **User Request Threads:** Virtual threads executing `put`, `get`, and `delete`.
- **Maintenance Threads:** Dedicated `ForkJoinPool` for background compaction and WAL rotation.
- **Metric Collection:** Lock-free atomic updates from any thread.
