# Architecture - Core Module

The `core` module provides the shared foundation for the entire `kv-store` engine. It defines common types, error handling, configuration, and the critical memory budgeting infrastructure.

## Key Components

### `EngineConfig`
Immutable configuration object built using a fluent `Builder`. It defines system-wide thresholds such as:
- `maxMemoryBytes`: Hard ceiling for RAM usage (default 100GB).
- `memtableMaxSizeBytes`: Size at which a MemTable is flushed to disk.
- `blockCacheMaxSizeBytes`: RAM allocated for block-level caching.
- `bloomFilterBitsPerKey`: Density of the Bloom filters.
- `walFsyncIntervalMillis`: Durability/performance tradeoff for WAL flushing.

### `MemoryBudget`
The "source of truth" for memory usage. All other modules must register and allocate memory through this service.
- **Enforcement:** Throws `StorageException(BUDGET_EXCEEDED)` if an allocation would exceed the configured limit.
- **Tracking:** Maintains per-component usage statistics (`wal`, `memtable`, `sstable`, `index`, `cache`).

### `StorageException` & `StorageErrorCode`
Standardized error handling system.
- **Corruption:** `CHECKSUM_MISMATCH`, `CORRUPT_WAL_RECORD`.
- **Resources:** `BUDGET_EXCEEDED`, `CACHE_EVICTION_FAILED`.
- **I/O & Durability:** `IO_ERROR`, `WAL_WRITE_FAILED`, `RECOVERY_FAILED`.
- **Concurrency:** `WRITE_BACKPRESSURE`, `LOCK_TIMEOUT`.

## Design Patterns

- **Immutability:** Configuration and Error objects are immutable.
- **Centralization:** Memory tracking is centralized to prevent "stealth" leaks.
- **Type Safety:** Use of specific ErrorCodes instead of generic exceptions.
