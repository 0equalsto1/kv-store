package com.kvstore.sstable;

import com.kvstore.cache.BlockCache;
import com.kvstore.core.MemoryBudget;
import com.kvstore.core.StorageErrorCode;
import com.kvstore.core.StorageException;
import com.kvstore.core.util.ByteArrayComparator;
import com.kvstore.indexing.BloomFilter;
import com.kvstore.indexing.SparseIndex;
import com.kvstore.io.MappedFileReader;

import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.nio.ByteOrder;
import java.nio.file.Path;
import java.util.Optional;

/**
 * Provides read access to an immutable SSTable file.
 * <p>
 * The reader uses a combination of techniques to optimize lookups:
 * <ul>
 *   <li><b>Bloom Filter:</b> To quickly rule out keys not present in the SSTable.</li>
 *   <li><b>Sparse Index:</b> To identify the approximate location (offset) of a key in the data section.</li>
 *   <li><b>Block Cache:</b> To minimize disk I/O by keeping frequently accessed data blocks in memory.</li>
 *   <li><b>Memory-Mapped I/O:</b> To efficiently access file content using the OS page cache.</li>
 * </ul>
 */
public class SstableReader implements AutoCloseable {
    private static final String COMPONENT_NAME = "sstable";
    private static final ValueLayout.OfInt JAVA_INT_BE = ValueLayout.JAVA_INT_UNALIGNED.withOrder(ByteOrder.BIG_ENDIAN);

    private final String sstableId;
    private final MappedFileReader reader;
    private final SstableHeader header;
    private final BloomFilter bloomFilter;
    private final SparseIndex sparseIndex;
    private final BlockCache blockCache;

    /**
     * Constructs an {@code SstableReader} for the specified file.
     * <p>
     * During initialization, the reader parses the SSTable header and loads the sparse index
     * into memory to prepare for lookups.
     *
     * @param path The path to the SSTable file.
     * @param budget The memory budget for tracking memory-mapped regions.
     * @param blockCache The cache for storing and retrieving data blocks.
     * @throws StorageException if the SSTable header is corrupt or invalid.
     */
    public SstableReader(Path path, MemoryBudget budget, BlockCache blockCache) {
        this.sstableId = path.getFileName().toString();
        this.reader = new MappedFileReader(path, budget, COMPONENT_NAME);
        this.blockCache = blockCache;

        this.header = SstableHeaderCodec.decode(reader.getSegment(), 0);
        if (header.magic() != SstableHeader.MAGIC) {
            throw new StorageException(StorageErrorCode.CORRUPT_SSTABLE_HEADER, "Invalid magic bytes in SSTable: " + path);
        }

        // For this MVP, we assume Bloom Filter is not yet stored in SSTable footer.
        // In a real engine, we'd load it here.
        this.bloomFilter = null; 

        // Load Sparse Index
        long indexSize = reader.getSize() - header.indexOffset();
        this.sparseIndex = new SparseIndex(reader.getSegment().asSlice(header.indexOffset(), indexSize));
    }

    /**
     * Retrieves the value associated with a target key.
     * <p>
     * The lookup follows a hierarchical path:
     * 1. Check Bloom Filter (if available) to potentially skip the lookup.
     * 2. Use the Sparse Index to find the starting offset of the block containing the key.
     * 3. Check the Block Cache for the identified block.
     * 4. Perform a sequential scan of the data section starting from the floor offset
     *    until the key is found, a larger key is encountered, or the data section ends.
     *
     * @param targetKey The key to search for.
     * @return A {@link ReadResult} containing the status and value (if found).
     */
    public ReadResult get(byte[] targetKey) {
        // 1. Bloom Filter (Skip if possible)
        if (bloomFilter != null && !bloomFilter.mightContain(targetKey)) {
            return ReadResult.notFound();
        }

        // 2. Sparse Index (Identify candidate offset)
        long dataOffset = sparseIndex.findFloorOffset(targetKey);
        if (dataOffset == -1) {
            // targetKey is smaller than the first key in this SSTable
            return ReadResult.notFound();
        }

        // 3. Block Cache
        Optional<MemorySegment> cachedBlock = blockCache.get(sstableId, dataOffset);
        if (cachedBlock.isPresent()) {
            return scanBlock(cachedBlock.get(), targetKey, 0);
        }

        // 4. SSD Scan
        // For now, we scan from the dataOffset until the end of the data section (header.indexOffset)
        long scanLimit = header.indexOffset();
        ReadResult result = scanBlock(reader.getSegment(), targetKey, dataOffset, scanLimit);
        
        // 5. Populate Cache on success
        if (result.status() != ReadStatus.NOT_FOUND) {
             // In a real engine, we'd cache the whole BLOCK. 
             // Here we'll just cache the segment if we can identify the block boundaries.
             // For simplicity, we skip caching in this step or cache the specific record.
        }
        
        return result;
    }

    /**
     * Scans a specific region of a memory segment for a target key.
     * <p>
     * This method iterates through records, verifying checksums and comparing keys.
     * Since SSTable records are sorted, the scan stops early if a key greater than
     * the target key is encountered.
     *
     * @param segment The memory segment to scan.
     * @param targetKey The key being searched for.
     * @param startOffset The offset in the segment to start the scan.
     * @param limit The offset in the segment to end the scan (exclusive).
     * @return The {@link ReadResult} of the scan.
     * @throws StorageException if a checksum mismatch is detected.
     */
    private ReadResult scanBlock(MemorySegment segment, byte[] targetKey, long startOffset, long limit) {
        long current = startOffset;
        while (current < limit) {
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
                throw new StorageException(StorageErrorCode.CHECKSUM_MISMATCH, 
                    String.format("Checksum mismatch in SSTable %s at offset %d", sstableId, recordStart));
            }

            byte[] key = new byte[keyLen];
            MemorySegment.copy(segment, current, MemorySegment.ofArray(key), 0, keyLen);
            current += keyLen;

            int cmp = ByteArrayComparator.INSTANCE.compare(key, targetKey);
            if (cmp == 0) {
                if (type == 2) return ReadResult.deleted();
                byte[] value = new byte[valLen];
                MemorySegment.copy(segment, current, MemorySegment.ofArray(value), 0, valLen);
                return ReadResult.found(value);
            } else if (cmp > 0) {
                // Since SSTable is sorted, if key > targetKey, we can stop
                break;
            }
            current += valLen;
            current += 4; // Skip the checksum we already verified
        }
        return ReadResult.notFound();
    }

    /**
     * Updates the CRC32 checksum with data from a memory segment.
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
     * Scans a cached block for a target key.
     *
     * @param block The memory segment representing the cached block.
     * @param targetKey The key being searched for.
     * @param offset The starting offset within the block.
     * @return The {@link ReadResult} of the scan.
     */
    private ReadResult scanBlock(MemorySegment block, byte[] targetKey, long offset) {
        // This variant is for already cached blocks
        return scanBlock(block, targetKey, offset, block.byteSize());
    }

    /**
     * Returns an iterator over all records in this SSTable.
     *
     * @return A new {@link SstableIterator} instance.
     */
    public SstableIterator iterator() {
        return new SstableIterator(reader.getSegment(), header.dataOffset(), header.indexOffset());
    }

    /**
     * Closes the reader and releases associated resources, including the memory-mapped file.
     */
    @Override
    public void close() {
        reader.close();
        if (bloomFilter != null) bloomFilter.close();
    }
}
