# Development Guide - kv-store

## Prerequisites

- **Java 26+**: The project utilizes latest features like Virtual Threads and Foreign Function & Memory API.
- **Gradle**: Used for build management and multi-module coordination.

## Getting Started

### Cloning the Repository
```bash
git clone https://github.com/mannu/kv-store.git
cd kv-store
```

### Building the Project
```bash
./gradlew build
```

### Running Tests
```bash
./gradlew test
```

## Module Structure

The project is organized into focused modules. When adding new features, identify the appropriate layer:

- **Data Path:** `memtable` → `wal` → `sstable`
- **Read Path:** `indexing` → `cache` → `sstable`
- **Maintenance:** `compaction`
- **Infrastructure:** `core`, `io`, `concurrency`, `telemetry`
- **Integration:** `engine`, `api`

## Coding Standards

### Memory Management
All allocations MUST be tracked via the `MemoryBudget` service. Never use `ByteBuffer.allocate()` or `Arena.allocate()` without registering the usage with the appropriate component tracker.

### Error Handling
Always throw `StorageException` with a relevant `StorageErrorCode`. Avoid generic `RuntimeException` or `IOException`.

### Concurrency
Prefer Virtual Threads for I/O-bound tasks. Use the `BackpressureSignal` to coordinate writes during heavy background maintenance.

## Testing Strategy

- **Unit Tests:** Located in each module's `src/test` folder. Focus on isolated component logic.
- **Integration Tests:** Located in the `integration-tests` module. Focus on cross-module scenarios and end-to-end flows.
- **Chaos Tests:** Special integration tests that simulate crashes and power failures to verify WAL recovery.
- **Benchmarks:** Located in the `benchmarks` module using JMH.
