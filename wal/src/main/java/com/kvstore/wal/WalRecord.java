package com.kvstore.wal;

import java.util.Arrays;
import java.util.Objects;

/**
 * Represents a single entry in the Write-Ahead Log (WAL).
 * <p>
 * Each record captures a discrete database operation (e.g., PUT or DELETE) that
 * must be persisted to the log before it is applied to the in-memory data structures.
 * This ensures durability in the event of a system crash.
 * <p>
 * Binary Layout:
 * <pre>
 * [version(1B) | type(1B) | keyLen(4B) | valueLen(4B) | key(var) | value(variable) | crc32(4B)]
 * </pre>
 *
 * @param version The version of the WAL record format.
 * @param type The operation type: {@link #TYPE_PUT} (0x01) or {@link #TYPE_DELETE} (0x02).
 * @param key The byte array representing the key.
 * @param value The byte array representing the value (empty for deletes).
 * @param crc32 A CRC32 checksum for data integrity verification.
 */
public record WalRecord(
    byte version,
    byte type,
    byte[] key,
    byte[] value,
    long crc32
) {
    /**
     * Initial version of the WAL record format.
     */
    public static final byte VERSION_1 = 0x01;

    /**
     * Indicates a PUT operation where a key-value pair is inserted or updated.
     */
    public static final byte TYPE_PUT = 0x01;

    /**
     * Indicates a DELETE operation (tombstone).
     */
    public static final byte TYPE_DELETE = 0x02;

    /**
     * Canonical constructor with non-null validation.
     *
     * @param version The record version.
     * @param type The operation type.
     * @param key The non-null key bytes.
     * @param value The non-null value bytes.
     * @param crc32 The checksum.
     */
    public WalRecord {
        Objects.requireNonNull(key, "key cannot be null");
        Objects.requireNonNull(value, "value cannot be null");
    }

    /**
     * Compares this record with another object for equality.
     * <p>
     * Performs deep equality checks on byte arrays and handles primitives.
     *
     * @param o The object to compare with.
     * @return {@code true} if equal; {@code false} otherwise.
     */
    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof WalRecord that)) return false;
        return version == that.version && type == that.type && crc32 == that.crc32 && 
               Arrays.equals(key, that.key) && Arrays.equals(value, that.value);
    }

    /**
     * Computes the hash code for this record.
     *
     * @return The computed hash code.
     */
    @Override
    public int hashCode() {
        int result = Objects.hash(version, type, crc32);
        result = 31 * result + Arrays.hashCode(key);
        result = 31 * result + Arrays.hashCode(value);
        return result;
    }

    /**
     * Returns a string representation of the record metadata.
     *
     * @return A descriptive string excluding full byte array contents for brevity.
     */
    @Override
    public String toString() {
        return "WalRecord{" +
                "version=" + version +
                ", type=" + type +
                ", keySize=" + key.length +
                ", valueSize=" + value.length +
                ", crc32=" + crc32 +
                '}';
    }
}
