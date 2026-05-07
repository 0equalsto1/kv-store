package com.kvstore.sstable;

import com.kvstore.core.util.ByteArrayComparator;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.util.Iterator;
import java.util.NoSuchElementException;

/**
 * An iterator that provides sequential access to the records within an SSTable.
 * <p>
 * This iterator scans the data section of the SSTable and reconstructs {@link Record}
 * objects. It also performs data integrity verification by checking the CRC32
 * checksum for each record during the scan.
 */
public class SstableIterator implements Iterator<SstableIterator.Record>, AutoCloseable {
    private static final ValueLayout.OfInt JAVA_INT_BE = ValueLayout.JAVA_INT_UNALIGNED.withOrder(java.nio.ByteOrder.BIG_ENDIAN);

    /**
     * Represents a single data record retrieved from an SSTable.
     *
     * @param type The type of the record (e.g., PUT or DELETE).
     * @param key The byte array representing the key.
     * @param value The byte array representing the value.
     */
    public record Record(byte type, byte[] key, byte[] value) {}

    private final MemorySegment segment;
    private final long limit;
    private long current;

    /**
     * Constructs a new {@code SstableIterator} over a specific region of a memory segment.
     *
     * @param segment The memory segment containing the SSTable data.
     * @param startOffset The offset in the segment where the data records start.
     * @param limit The offset in the segment where the data records end (exclusive).
     */
    public SstableIterator(MemorySegment segment, long startOffset, long limit) {
        this.segment = segment;
        this.current = startOffset;
        this.limit = limit;
    }

    /**
     * Checks if there are more records to iterate over.
     *
     * @return {@code true} if there is at least one more record; {@code false} otherwise.
     */
    @Override
    public boolean hasNext() {
        return current < limit;
    }

    /**
     * Fetches the next record from the SSTable.
     * <p>
     * This method advances the internal offset, reads the record metadata,
     * verifies its checksum, and extracts the key and value.
     *
     * @return The next {@link Record} in the sequence.
     * @throws NoSuchElementException if no more records are available.
     * @throws com.kvstore.core.StorageException if a checksum mismatch is detected.
     */
    @Override
    public Record next() {
        if (!hasNext()) throw new NoSuchElementException();

        long recordStart = current;
        byte type = segment.get(ValueLayout.JAVA_BYTE, current++);
        int keyLen = segment.get(JAVA_INT_BE, current);
        current += 4;
        int valLen = segment.get(JAVA_INT_BE, current);
        current += 4;

        long payloadSize = 1L + 8L + keyLen + valLen;

        // Verify checksum
        java.util.zip.CRC32 crc = new java.util.zip.CRC32();
        updateCrc(crc, segment.asSlice(recordStart, payloadSize));
        int expectedChecksum = (int) crc.getValue();
        int actualChecksum = segment.get(JAVA_INT_BE, recordStart + payloadSize);

        if (expectedChecksum != actualChecksum) {
            throw new com.kvstore.core.StorageException(com.kvstore.core.StorageErrorCode.CHECKSUM_MISMATCH,
                String.format("Checksum mismatch during scan at offset %d", recordStart));
        }

        byte[] key = new byte[keyLen];
        MemorySegment.copy(segment, current, MemorySegment.ofArray(key), 0, keyLen);
        current += keyLen;

        byte[] value = new byte[valLen];
        MemorySegment.copy(segment, current, MemorySegment.ofArray(value), 0, valLen);
        current += valLen;

        current += 4; // Skip checksum

        return new Record(type, key, value);
    }

    /**
     * Updates the CRC32 checksum with data from a memory segment.
     * <p>
     * This method handles segments larger than {@link Integer#MAX_VALUE} by
     * processing them in chunks.
     *
     * @param crc The CRC32 instance to update.
     * @param segment The memory segment containing the data.
     */
    private void updateCrc(java.util.zip.CRC32 crc, MemorySegment segment) {
        if (segment.byteSize() <= Integer.MAX_VALUE) {
            crc.update(segment.asByteBuffer());
        } else {
            long offset = 0;
            while (offset < segment.byteSize()) {
                long len = Math.min(segment.byteSize() - offset, Integer.MAX_VALUE);
                crc.update(segment.asSlice(offset, len).asByteBuffer());
                offset += len;
            }
        }
    }

    /**
     * Closes the iterator. For this memory-mapped implementation, this is a no-op.
     */
    @Override
    public void close() {
        // No-op for segment iterator
    }
}
