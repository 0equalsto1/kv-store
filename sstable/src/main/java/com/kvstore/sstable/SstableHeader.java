package com.kvstore.sstable;

/**
 * Represents the metadata header at the beginning of an SSTable file.
 * <p>
 * The header has a fixed size and contains critical information for navigating
 * and validating the SSTable, such as versioning, record counts, and offsets
 * to the index and data sections.
 * <p>
 * Binary Layout (Big Endian):
 * <pre>
 * [Magic(4B) | Version(1B) | KeyCount(8B) | IndexOffset(8B) | DataOffset(8B) | CreatedTime(8B) | Checksum(4B)]
 * </pre>
 *
 * @param magic A 4-byte unique identifier ("KVSS") to verify the file format.
 * @param version The version of the SSTable format, allowing for backward compatibility.
 * @param keyCount The total number of unique keys stored in this SSTable.
 * @param indexOffset The byte offset from the start of the file where the index section begins.
 * @param dataOffset The byte offset from the start of the file where the data records begin.
 * @param createdTime The timestamp (in milliseconds since epoch) when this SSTable was created.
 * @param checksum A CRC32 checksum of the header and/or contents to ensure data integrity.
 */
public record SstableHeader(
    int magic,
    byte version,
    long keyCount,
    long indexOffset,
    long dataOffset,
    long createdTime,
    long checksum
) {
    /**
     * The unique magic number identifying a valid SSTable file.
     */
    public static final int MAGIC = 0x4B565353; // "KVSS"

    /**
     * The initial version of the SSTable format.
     */
    public static final byte VERSION_1 = 0x01;

    /**
     * The fixed size of the SSTable header in bytes.
     */
    public static final int HEADER_SIZE = 4 + 1 + 8 + 8 + 8 + 8 + 4; // 41 bytes
}
