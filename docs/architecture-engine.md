# Architecture - Engine Module

The `engine` module is the main orchestrator of the `kv-store`. It implements the `StorageEngine` class, which coordinates all other modules to provide a cohesive KV store.

## Lifecycle Management

### Initialization (`start()`)
1. **Directories:** Creates `wal/` and `data/` directories.
2. **SSTables:** Scans the data directory and loads existing `SstableReader` instances.
3. **MemTable:** Initializes a fresh `MemTableImpl`.
4. **Recovery:** Invokes `EngineRecovery` to replay any pending WAL records into the MemTable (flushing to disk if thresholds are exceeded).
5. **WAL:** Opens a new `WalWriter` for incoming operations.
6. **Compaction:** Starts the `CompactionScheduler` to manage background maintenance.

### Shutdown (`close()`)
Performs a graceful shutdown of all components:
1. Stops the compaction scheduler.
2. Closes and flushes the WAL writer.
3. Closes all SSTable readers.
4. Clears the block cache and releases memory budget.

## Data Flow Orchestration

### Write Path (`put`, `delete`)
1. **Backpressure:** Checks `WriteBackpressure` to ensure the system isn't overwhelmed by compaction lag.
2. **WAL:** Appends the operation to the Write-Ahead Log.
3. **MemTable:** Updates the in-memory SkipList.
4. **Flush Check:** If the MemTable exceeds its size threshold, it triggers a background flush to a new SSTable and rotates the WAL.

### Read Path (`get`)
1. **MemTable:** First check for the latest version of the key.
2. **SSTables:** Scans `SstableReader` instances in reverse chronological order (newest first).
   - Uses Bloom Filter for fast non-existence check.
   - Uses Sparse Index to find the candidate data block.
   - Checks Block Cache before performing SSD I/O.

## Recovery Logic

The `EngineRecovery` class scans the `wal/` directory for `.wal` files. It replays each record into a temporary MemTable. If the MemTable fills up during recovery, it is flushed to a new SSTable just like during normal operation. This ensures that even very large WALs can be recovered without exceeding memory limits.
