# Architecture - MemTable Module

The `memtable` module provides an in-memory, thread-safe buffer for incoming write operations.

## Key Components

### `MemTable` (Interface)
Defines the contract for the in-memory store, including `put`, `get`, `delete`, and `immutableSnapshot`.

### `MemTableImpl`
The primary implementation using a `ConcurrentSkipListMap`.
- **Concurrency:** Leverages Java's highly optimized lock-free skip list for concurrent access.
- **Tombstones:** Deletions are stored as special `ValueEntry` records with a tombstone flag.
- **Memory Tracking:** Tracks the overhead of each entry (pointers + data) against the `MemoryBudget`.

## Lifecycle

1. **Active:** Receives all writes after they are logged to the WAL.
2. **Snapshot:** When the size threshold (default 64MB) is reached, the engine takes an `immutableSnapshot` of the MemTable.
3. **Flush:** The snapshot is passed to the `SstableWriter` to be persisted to disk.
4. **Clear:** Once the flush is successful, the MemTable is cleared and its memory is reclaimed in the budget.

## Design Tradeoffs

- **Ordering:** The SkipList keeps keys sorted, which is essential for efficient SSTable generation.
- **Overhead:** Estimated at ~64 bytes per entry in addition to the raw key/value bytes.
