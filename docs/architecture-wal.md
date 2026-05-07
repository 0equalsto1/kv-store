# Architecture - WAL Module

The `wal` (Write-Ahead Log) module is responsible for the durability of all write operations in the `kv-store`.

## Key Components

### `WalWriter`
Appends records to the current active WAL file.
- **Group Commits:** Supports batching multiple writes before calling `force()`.
- **Zero-Copy Encoding:** Encodes records directly into the `MappedFileWriter` segment using `WalRecordCodec`.

### `WalRecord`
A Java `record` representing a single entry in the log.
- **Fields:** Version, Type (PUT/DELETE), Key, Value, and a CRC32 checksum.
- **Immutability:** Ensures data integrity during the write process.

### `WalRecordCodec`
Handles the serialization and deserialization of `WalRecord` objects.
- **Format:** `[version(1B) | type(1B) | keyLen(4B) | valueLen(4B) | key(var) | value(variable) | crc32(4B)]`.

## Durability Protocol

1. **Append:** The record is encoded into the memory-mapped WAL file.
2. **Checksum:** A CRC32 is calculated and appended to the record to detect partial writes or corruption.
3. **Sync:** The engine periodically (or on specific triggers) calls `force()` on the WAL file to ensure data is persisted to the SSD.
4. **Acknowledgement:** The operation is only acknowledged to the user after the WAL has been successfully synced.
