package com.kvstore.wal;

import com.kvstore.core.StorageErrorCode;
import com.kvstore.core.StorageException;

import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.util.zip.CRC32;

/**
 * Utility class for encoding and decoding {@link WalRecord} objects.
 * <p>
 * This codec provides logic for serializing WAL records into a packed binary
 * format and reconstructing them while verifying data integrity using CRC32 checksums.
 * It uses unaligned value layouts to support efficient zero-copy access within
 * memory-mapped files.
 */
public class WalRecordCodec {
    /**
     * The fixed-size portion of the record header in bytes.
     * header: version(1) + type(1) + kLen(4) + vLen(4)
     */
    private static final int HEADER_SIZE = 10;

    /**
     * The size of the CRC32 checksum field in bytes.
     */
    private static final int CHECKSUM_SIZE = 4;

    /**
     * Safety limit for a single record size (100MB) to prevent OOM or corruption issues.
     */
    private static final int MAX_RECORD_SIZE = 100 * 1024 * 1024; // 100MB safety limit

    // Use unaligned value layouts for packed binary formats
    private static final ValueLayout.OfInt JAVA_INT_UNALIGNED = ValueLayout.JAVA_INT.withByteAlignment(1);

    /**
     * Calculates the total binary size required for a given {@link WalRecord}.
     *
     * @param record The record to measure.
     * @return The size in bytes.
     */
    public static long calculateSize(WalRecord record) {
        return (long) HEADER_SIZE + record.key().length + record.value().length + CHECKSUM_SIZE;
    }

    /**
     * Encodes a {@link WalRecord} into the specified {@link MemorySegment}.
     * <p>
     * The method writes the header, key, and value to the segment, calculates
     * a CRC32 checksum over those fields, and appends the checksum at the end.
     *
     * @param record The record to encode.
     * @param destination The memory segment to write into.
     * @param offset The starting offset within the destination segment.
     * @throws StorageException if the record size exceeds the safety limit.
     */
    public static void encode(WalRecord record, MemorySegment destination, long offset) {
        long size = calculateSize(record);
        if (size > MAX_RECORD_SIZE) {
            throw new StorageException(StorageErrorCode.INVALID_CONFIGURATION, "Record too large: " + size);
        }

        long currentOffset = offset;
        destination.set(ValueLayout.JAVA_BYTE, currentOffset++, record.version());
        destination.set(ValueLayout.JAVA_BYTE, currentOffset++, record.type());
        destination.set(JAVA_INT_UNALIGNED, currentOffset, record.key().length);
        currentOffset += 4;
        destination.set(JAVA_INT_UNALIGNED, currentOffset, record.value().length);
        currentOffset += 4;

        MemorySegment.copy(MemorySegment.ofArray(record.key()), 0, destination, currentOffset, record.key().length);
        currentOffset += record.key().length;
        MemorySegment.copy(MemorySegment.ofArray(record.value()), 0, destination, currentOffset, record.value().length);
        currentOffset += record.value().length;

        // Calculate CRC32 over the encoded data (excluding the checksum field itself)
        long dataSize = (long) HEADER_SIZE + record.key().length + record.value().length;
        long crc = computeChecksum(destination.asSlice(offset, dataSize));
        destination.set(JAVA_INT_UNALIGNED, currentOffset, (int) crc);
    }

    /**
     * Decodes a {@link WalRecord} from the specified {@link MemorySegment}.
     * <p>
     * This method reads the header to determine field lengths, extracts the data,
     * and validates the integrity of the record by comparing the stored checksum
     * with a newly calculated one.
     *
     * @param source The memory segment to read from.
     * @param offset The starting offset of the record within the source segment.
     * @return A new {@link WalRecord} instance populated from the binary data.
     * @throws StorageException if record lengths are invalid, the size exceeds
     *                          the safety limit, or a checksum mismatch is detected.
     */
    public static WalRecord decode(MemorySegment source, long offset) {
        long currentOffset = offset;
        byte version = source.get(ValueLayout.JAVA_BYTE, currentOffset++);
        byte type = source.get(ValueLayout.JAVA_BYTE, currentOffset++);
        int keyLen = source.get(JAVA_INT_UNALIGNED, currentOffset);
        currentOffset += 4;
        int valueLen = source.get(JAVA_INT_UNALIGNED, currentOffset);
        currentOffset += 4;

        if (keyLen < 0 || valueLen < 0 || (long) HEADER_SIZE + keyLen + valueLen + CHECKSUM_SIZE > MAX_RECORD_SIZE) {
            throw new StorageException(StorageErrorCode.CORRUPT_WAL_RECORD, "Invalid or excessive record lengths");
        }

        byte[] key = new byte[keyLen];
        MemorySegment.copy(source, currentOffset, MemorySegment.ofArray(key), 0, keyLen);
        currentOffset += keyLen;

        byte[] value = new byte[valueLen];
        MemorySegment.copy(source, currentOffset, MemorySegment.ofArray(value), 0, valueLen);
        currentOffset += valueLen;

        int storedCrc = source.get(JAVA_INT_UNALIGNED, currentOffset);
        
        // Verify CRC32
        long dataSize = (long) HEADER_SIZE + keyLen + valueLen;
        long calculatedCrc = computeChecksum(source.asSlice(offset, dataSize));
        
        if ((int) calculatedCrc != storedCrc) {
            throw new StorageException(StorageErrorCode.CHECKSUM_MISMATCH, 
                String.format("WAL record checksum mismatch. Expected: %d, Actual: %d", storedCrc, (int) calculatedCrc));
        }

        return new WalRecord(version, type, key, value, calculatedCrc & 0xFFFFFFFFL);
    }

    /**
     * Computes the CRC32 checksum for a given memory segment slice.
     * <p>
     * Since this is used for individual records limited to {@link #MAX_RECORD_SIZE},
     * the segment is guaranteed to be within the 2GB limit of {@link java.nio.ByteBuffer}.
     *
     * @param segment The memory segment containing the data to checksum.
     * @return The calculated CRC32 value.
     */
    private static long computeChecksum(MemorySegment segment) {
        CRC32 crc = new CRC32();
        // segments can be > 2GB, but here we only checksum ONE record (limited to 100MB)
        // so asByteBuffer() is safe here.
        crc.update(segment.asByteBuffer());
        return crc.getValue();
    }
}
