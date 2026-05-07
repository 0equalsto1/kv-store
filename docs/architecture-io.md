# Architecture - IO Module

The `io` module provides high-performance, zero-copy file I/O abstractions using Java's Foreign Function & Memory (FFM) API.

## Key Components

### `MappedFileWriter`
Manages a `MemorySegment` mapped to a file in `READ_WRITE` mode.
- **Zero-Copy:** Writes data directly into the mapped memory.
- **Budget-Aware:** Registers its memory usage with the `MemoryBudget`.
- **Durability:** Provides a `force()` method to flush changes to physical storage.

### `MappedFileReader`
Manages a `MemorySegment` mapped in `READ_ONLY` mode.
- **Efficiency:** Allows random access to large files without loading them into the JVM heap.
- **Lifecycle:** Uses `Arena` for deterministic resource deallocation.

### `FileArenaPool`
(Internal) Manages a pool of arenas for efficient memory segment lifecycle management across concurrent operations.

## Design Considerations

- **Safety:** Uses `Arena.ofShared()` to allow concurrent access by multiple virtual threads while ensuring safe closure.
- **Alignment:** Ensures memory segments are correctly aligned (e.g., 8-byte alignment for long values).
- **Overflow Protection:** Uses `Math.addExact` for offset calculations to prevent arithmetic overflows during large file access.
