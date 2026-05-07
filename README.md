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
| [core](./docs/architecture-core.md) | Shared types, exceptions, configuration, and memory budgeting. |
| [io](./docs/architecture-io.md) | MappedByteBuffer wrappers and I/O abstractions. |
| [indexing](./docs/architecture-indexing.md) | Sparse index and Bloom filter implementations. |
| [cache](./docs/architecture-cache.md) | Block-level LRU/LFU caching. |
| [wal](./docs/architecture-wal.md) | Write-Ahead Log for durability and recovery. |
| [memtable](./docs/architecture-memtable.md) | In-memory write buffer. |
| [sstable](./docs/architecture-sstable.md) | Disk-resident sorted string tables. |
| [compaction](./docs/architecture-compaction.md) | Background LSM maintenance and merging. |
| [concurrency](./docs/architecture-concurrency.md) | Virtual Thread coordination and backpressure. |
| [telemetry](./docs/architecture-telemetry.md) | Metrics and observability API. |
| [engine](./docs/architecture-engine.md) | Core orchestrator and lifecycle management. |
| [api](./docs/api-contracts.md) | Public KV API entry point. |

## Generated Documentation

- [Project Overview](./docs/project-overview.md)
- [Source Tree Analysis](./docs/source-tree-analysis.md)
- [API Contracts](./docs/api-contracts.md)
- [Data Models](./docs/data-models.md)
- [Development Guide](./docs/development-guide.md)
- [Architecture - Core](./docs/architecture-core.md)
- [Architecture - Engine](./docs/architecture-engine.md)
- [Architecture - IO](./docs/architecture-io.md)
- [Architecture - WAL](./docs/architecture-wal.md)
- [Architecture - MemTable](./docs/architecture-memtable.md)
- [Architecture - SSTable](./docs/architecture-sstable.md)
- [Architecture - Compaction](./docs/architecture-compaction.md)
- [Architecture - Indexing](./docs/architecture-indexing.md)
- [Architecture - Cache](./docs/architecture-cache.md)
- [Architecture - Telemetry](./docs/architecture-telemetry.md)
- [Architecture - Concurrency](./docs/architecture-concurrency.md)

## Existing Documentation

- [Product Requirements Document (PRD)](./_bmad-output/planning-artifacts/prd.md)
- [Architecture Decision Document](./_bmad-output/planning-artifacts/architecture.md)
- [Documentation Index](./docs/index.md)

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
