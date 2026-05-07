# Project Documentation Index - kv-store

## Project Overview

- **Type:** Multi-module Java Monorepo
- **Primary Language:** Java 26
- **Architecture:** LSM-tree (Log-Structured Merge-tree)

The `kv-store` is a high-performance, modular key-value storage engine implemented in native Java. It provides production-grade reliability and scalability, managing trillions of keys on SSD within a 100GB RAM footprint.

## Quick Reference

### Tech Stack
- **Language:** Java 26 (Virtual Threads, FFM API)
- **Build System:** Gradle
- **Testing:** JUnit 5
- **Architecture Pattern:** LSM-tree, Write-Ahead Log (WAL), SSTables, Bloom Filters

### Modules

| Module | Purpose |
|--------|---------|
| [core](./architecture-core.md) | Shared types, exceptions, configuration, and memory budgeting. |
| [io](./architecture-io.md) | MappedByteBuffer wrappers and I/O abstractions. |
| [indexing](./architecture-indexing.md) | Sparse index and Bloom filter implementations. |
| [cache](./architecture-cache.md) | Block-level LRU/LFU caching. |
| [wal](./architecture-wal.md) | Write-Ahead Log for durability and recovery. |
| [memtable](./architecture-memtable.md) | In-memory write buffer. |
| [sstable](./architecture-sstable.md) | Disk-resident sorted string tables. |
| [compaction](./architecture-compaction.md) | Background LSM maintenance and merging. |
| [concurrency](./architecture-concurrency.md) | Virtual Thread coordination and backpressure. |
| [telemetry](./architecture-telemetry.md) | Metrics and observability API. |
| [engine](./architecture-engine.md) | Core orchestrator and lifecycle management. |
| [api](./api-contracts.md) | Public KV API entry point. |

## Generated Documentation

- [Project Overview](./project-overview.md)
- [Source Tree Analysis](./source-tree-analysis.md)
- [API Contracts](./api-contracts.md)
- [Data Models](./data-models.md)
- [Development Guide](./development-guide.md)
- [Architecture - Core](./architecture-core.md)
- [Architecture - Engine](./architecture-engine.md)
- [Architecture - IO](./architecture-io.md)
- [Architecture - WAL](./architecture-wal.md)
- [Architecture - MemTable](./architecture-memtable.md)
- [Architecture - SSTable](./architecture-sstable.md)
- [Architecture - Compaction](./architecture-compaction.md)
- [Architecture - Indexing](./architecture-indexing.md)
- [Architecture - Cache](./architecture-cache.md)
- [Architecture - Telemetry](./architecture-telemetry.md)
- [Architecture - Concurrency](./architecture-concurrency.md)

## Existing Documentation

- [Product Requirements Document (PRD)](../_bmad-output/planning-artifacts/prd.md)
- [Architecture Decision Document](../_bmad-output/planning-artifacts/architecture.md)
- [HELP.md](../readme.md)

## Getting Started

1. **Build:** `./gradlew build`
2. **Test:** `./gradlew test`
3. **Usage:**
   ```java
   EngineConfig config = new EngineConfig.Builder().build();
   StorageEngine engine = new StorageEngine(config, Path.of("/data"));
   engine.start();
   engine.put("key".getBytes(), "value".getBytes());
   Optional<byte[]> val = engine.get("key".getBytes());
   ```
