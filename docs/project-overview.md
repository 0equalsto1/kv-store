# Project Overview - kv-store

## Executive Summary

The `kv-store` is a high-performance, modular key-value storage engine implemented in native Java. It provides production-grade reliability and scalability, managing trillions of keys on SSD within a 100GB RAM footprint. By utilizing core Java libraries and avoiding external database systems, the project delivers a transparent, embeddable storage layer with predictable millisecond-level latency and high throughput for large-scale data workloads.

## Key Features

- **LSM-Tree Core:** High-performance Log-Structured Merge-tree architecture optimized for SSDs.
- **Write-Ahead Log (WAL):** 100% data durability for acknowledged writes across hard power failures.
- **Sparse Indexing:** Efficient memory footprint for trillion-key scale datasets.
- **Bloom Filters:** O(1) non-existence checks to minimize disk I/O.
- **Memory Budgeting:** Hard enforcement of RAM limits across all engine components.
- **Telemetry:** Built-in observability for latency, throughput, and system health.
- **Native Java:** Pure Java 26 implementation using Virtual Threads and FFM API.

## Core Technical Specifications

| Metric | Target |
|--------|--------|
| **Latency (P99)** | < 5ms on NVMe SSD |
| **Throughput** | > 100,000 OPS (70/30 read/write) |
| **RAM Footprint** | Configurable, defaults to 100GB |
| **Durability** | WAL-based, recovery < 60s for 1B keys |

## Documentation Roadmap

- [Master Index](./index.md) - Your starting point for navigation.
- [API Contracts](./api-contracts.md) - How to use the engine.
- [Architecture](./architecture-engine.md) - Detailed design of the core orchestrator.
- [Data Models](./data-models.md) - Internal and persistent data formats.
- [Source Tree Analysis](./source-tree-analysis.md) - Folder structure and responsibilities.
- [Development Guide](./development-guide.md) - How to build, test, and contribute.
