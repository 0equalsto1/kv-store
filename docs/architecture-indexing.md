# Architecture - Indexing Module

The `indexing` module provides the high-performance routing layer that enables sub-5ms read latency.

## Key Components

### `SparseIndex`
An in-memory mapping of keys to their offsets within an SSTable.
- **Sampling:** To minimize RAM usage, only every 128th key is stored in the index.
- **Binary Search:** Provides O(log N) lookup to find the "floor" offset of a target key.
- **Zero-Copy:** Operates directly on the memory-mapped index section of the SSTable file.

### `BloomFilter`
A probabilistic data structure used for fast non-existence checks.
- **Goal:** Minimizes unnecessary SSD I/O by identifying keys that are definitely not in an SSTable.
- **Implementation:** Uses MurmurHash3 and an off-heap bit array with atomic updates.
- **Efficiency:** Typically configured for a <1% false positive rate at 10 bits per key.

## Data Routing Flow

1. **Bloom Filter:** If `mightContain(key)` is false, the SSTable is skipped entirely.
2. **Sparse Index:** If the filter passes, `findFloorOffset(key)` identifies the starting point for a sequential scan.
3. **Sequential Scan:** The engine reads from the identified offset until the target key is found or the next indexed key is reached.
