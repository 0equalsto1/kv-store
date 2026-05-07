package com.kvstore.wal;

import com.kvstore.core.MemoryBudget;
import com.kvstore.core.StorageErrorCode;
import com.kvstore.core.StorageException;
import com.kvstore.io.MappedFileReader;

import java.lang.foreign.ValueLayout;
import java.nio.file.Path;
import java.util.Iterator;
import java.util.NoSuchElementException;

/**
 * Provides read access to a Write-Ahead Log (WAL) file.
 * <p>
 * This class is primarily used during database recovery to replay operations
 * and reconstruct the in-memory {@code MemTable}. It uses memory-mapped I/O
 * for efficient scanning of the log.
 * <p>
 * The reader implements {@link Iterable}, allowing for easy iteration over
 * {@link WalRecord} objects stored in the log.
 */
public class WalReader implements AutoCloseable, Iterable<WalRecord> {
    private final MappedFileReader reader;
    private static final ValueLayout.OfInt JAVA_INT_UNALIGNED = ValueLayout.JAVA_INT.withByteAlignment(1);

    /**
     * Constructs a {@code WalReader} for the specified file.
     *
     * @param path The path to the WAL file.
     * @param budget The memory budget for tracking memory-mapped regions.
     */
    public WalReader(Path path, MemoryBudget budget) {
        // Correctly attribute to "wal" component
        this.reader = new MappedFileReader(path, budget, "wal");
    }

    /**
     * Returns an iterator over the {@link WalRecord}s in this log.
     *
     * @return A new {@code WalIterator} instance.
     */
    @Override
    public Iterator<WalRecord> iterator() {
        return new WalIterator();
    }

    /**
     * Internal iterator implementation for scanning {@link WalRecord}s.
     * <p>
     * The iterator is designed to be resilient during recovery. It stops
     * at the first sign of corruption (checksum mismatch) or uninitialized
     * space, which typically marks the end of the valid log after a crash.
     */
    private class WalIterator implements Iterator<WalRecord> {
        private long offset = 0;
        private WalRecord nextRecord = null;
        private boolean stopRequested = false;

        /**
         * Checks if there are more valid records to read.
         * <p>
         * This method performs several safety checks:
         * <ul>
         *   <li>Verifies if enough bytes remain for a record header.</li>
         *   <li>Checks for uninitialized (zeroed) space.</li>
         *   <li>Ensures the full record size does not exceed the remaining file size.</li>
         * </ul>
         *
         * @return {@code true} if a valid record is available; {@code false} otherwise.
         */
        @Override
        public boolean hasNext() {
            if (stopRequested) return false;
            if (nextRecord != null) return true;
            if (offset >= reader.getSize()) return false;

            try {
                // Header check
                if (offset + 10 > reader.getSize()) {
                    return false; 
                }

                // Uninitialized space check
                if (reader.getSegment().get(ValueLayout.JAVA_BYTE, offset) == 0) {
                    return false;
                }

                int keyLen = reader.getSegment().get(JAVA_INT_UNALIGNED, offset + 2);
                int valueLen = reader.getSegment().get(JAVA_INT_UNALIGNED, offset + 6);
                long recordSize = 10L + keyLen + valueLen + 4;

                if (offset + recordSize > reader.getSize()) {
                    return false; // Truncated
                }

                nextRecord = WalRecordCodec.decode(reader.getSegment(), offset);
                offset += recordSize;
                return true;
            } catch (StorageException e) {
                // For recovery, we stop at the first sign of corruption or checksum mismatch
                // assuming this is the end of the valid log after a crash.
                stopRequested = true;
                return false;
            } catch (Exception e) {
                stopRequested = true;
                return false;
            }
        }

        /**
         * Retrieves the next {@link WalRecord}.
         *
         * @return The next record in the log.
         * @throws NoSuchElementException if no more valid records are available.
         */
        @Override
        public WalRecord next() {
            if (!hasNext()) throw new NoSuchElementException();
            WalRecord record = nextRecord;
            nextRecord = null;
            return record;
        }
    }

    /**
     * Closes the reader and releases associated memory-mapped resources.
     */
    @Override
    public void close() {
        reader.close();
    }
}
