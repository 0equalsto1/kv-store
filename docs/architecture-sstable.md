# Architecture - SSTable Module

The `sstable` (Sorted String Table) module handles the persistent, immutable storage of key-value pairs on disk.

## Key Components

### `SstableWriter`
Generates a new SSTable file from a `MemTable` snapshot or during compaction.
- **Atomic Operations:** Writes to a `.tmp` file and uses atomic rename to finalize.
- **Index Generation:** Samples keys every 128 records to build the `SparseIndex`.
- **Checksumming:** Calculates per-record CRC32 and a global header checksum.

### `SstableReader`
Provides high-performance random and sequential access to an existing SSTable.
- **Layered Lookup:** Bloom Filter → Sparse Index → Block Cache → SSD Scan.
- **Efficiency:** Uses `MappedFileReader` for zero-copy access to data and index sections.

### `SstableIterator`
Enables sequential scanning of an SSTable, used primarily during background compaction.

## File Format

- **Header:** Contains metadata, offsets to index/data sections, and versioning info.
- **Data Section:** Continuous stream of records, each with its own type, length, and checksum.
- **Index Section:** Sparse mapping of keys to their offsets in the data section.
- **Footer:** Contains the entry count and an array of relative offsets for fast index navigation.

## Data Integrity

Every record in an SSTable is protected by a CRC32 checksum. The `SstableReader` validates these checksums during every read operation. If a mismatch is detected, a `StorageException(CHECKSUM_MISMATCH)` is thrown to prevent silent data corruption.
