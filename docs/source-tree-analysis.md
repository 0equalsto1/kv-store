# Source Tree Analysis - kv-store

## Directory Structure

```
kv-store/
├── api/              # Public API entry points (KvStore)
├── benchmarks/       # JMH performance benchmarks
├── cache/            # Block-level caching (LRU/LFU)
├── compaction/       # Background LSM maintenance and merging
├── concurrency/      # Virtual Thread coordination and backpressure
├── core/             # Shared types, exceptions, and memory budgeting
├── docs/             # Project documentation (This folder)
├── engine/           # Main orchestrator (StorageEngine)
├── indexing/         # Sparse index and Bloom filters
├── integration-tests/# End-to-end and chaos tests
├── io/               # MappedByteBuffer and file abstractions
├── memtable/         # In-memory write buffer
├── sstable/          # Persistent sorted string tables
├── telemetry/        # Metrics and observability
├── tools/            # Out-of-band maintenance tools
└── wal/              # Write-Ahead Log durability
```

## Critical Folders

### `core/`
The foundation of the system. Contains `EngineConfig`, `StorageException`, and the critical `MemoryBudget` service which enforces the 100GB limit.

### `engine/`
Contains `StorageEngine`, the primary orchestrator that coordinates between MemTable, WAL, SSTables, and Compaction.

### `io/`
Abstraction over Java's Foreign Function & Memory (FFM) API for zero-copy file mapping via `MappedFileWriter` and `MappedFileReader`.

### `wal/`
Implements the durability protocol. Every write is logged here before being acknowledged to the user.

### `sstable/`
Handles the generation and reading of persistent, immutable sorted files on disk.

### `compaction/`
The "garbage collector" of the LSM-tree. Merges multiple SSTables into new ones, reclaiming space by removing duplicates and tombstones.
