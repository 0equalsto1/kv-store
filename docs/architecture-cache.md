# Architecture - Cache Module

The `cache` module implements a block-level caching layer to reduce SSD I/O for frequently accessed data.

## Key Components

### `BlockCache` (Interface)
Defines the contract for storing and retrieving memory segments associated with specific SSTable blocks.

### `LruBlockCache`
A least-recently-used cache implementation.
- **Eviction:** Automatically removes the oldest entries when the memory limit is reached.
- **Memory Management:** Coordinates with the `MemoryBudget` to ensure the cache never exceeds its allocated 8GB (default).

## Caching Strategy

- **Block-Level:** Rather than individual key-value pairs, the cache stores entire data blocks (typically 4KB-64KB).
- **Populate-on-Read:** Blocks are loaded into the cache during `get()` operations if they are not already present.
- **Zero-Copy Integration:** Cached blocks are managed as `MemorySegment` instances, allowing direct access without heap allocations.
