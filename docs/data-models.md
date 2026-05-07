# Data Models - kv-store

## Persistent Storage Formats

### Write-Ahead Log (WAL) Record
Append-only log for durability.
- **Format:** `[version(1B) | type(1B) | keyLen(4B) | valueLen(4B) | key(var) | value(variable) | crc32(4B)]`
- **Types:** `0x01` (PUT), `0x02` (DELETE).

### SSTable (Sorted String Table)
Immutable disk-based storage.
- **Header:** `[magic(4B) | version(1B) | keyCount(8B) | indexOffset(8B) | dataOffset(8B) | createdTime(8B) | globalChecksum(8B)]`
- **Data Record:** `[type(1B) | keyLen(4B) | valueLen(4B) | key(var) | value(variable) | recordChecksum(4B)]`
- **Index Entry:** `[keyLen(4B) | key(var) | dataOffset(8B)]`
- **Footer:** `[relativeOffsets(var) | entryCount(8B)]`

## In-Memory Structures

### MemTable
- **Implementation:** `ConcurrentSkipListMap<byte[], ValueEntry>`
- **Overhead:** ~64 bytes per node + key/value size.
- **Tombstones:** Represented by a `ValueEntry` with `isTombstone = true`.

### Sparse Index
- **Structure:** Array of `IndexEntry` (Key + Offset) sampled every 128 records.
- **Search:** Binary search for the floor offset of a target key.

### Bloom Filter
- **Implementation:** Bit array using off-heap `MemorySegment` with Atomic CAS updates.
- **Hash:** MurmurHash3 128-bit.
- **Footprint:** Configurable bits per key (default 10).

### Memory Budgeting
- **Central Service:** `MemoryBudget` tracks allocations across all components.
- **Limit:** Hard 100GB ceiling (default, configurable via `EngineConfig`).
