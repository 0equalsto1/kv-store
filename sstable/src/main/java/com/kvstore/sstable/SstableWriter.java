package com.kvstore.sstable;

import com.kvstore.core.MemoryBudget;
import com.kvstore.core.StorageErrorCode;
import com.kvstore.core.StorageException;
import com.kvstore.io.MappedFileWriter;
import com.kvstore.memtable.MemTable;

import java.io.IOException;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.zip.CRC32;

/**
 * Responsible for creating new SSTable files from in-memory data.
 * <p>
 * This class handles the serialization of records, generation of sparse indexes,
 * and ensuring data integrity through per-record and global checksums.
 * It uses a write-to-temporary-and-rename approach to ensure that SSTables are
 * created atomically.
 */
public class SstableWriter implements AutoCloseable {
    private static final String DEFAULT_COMPONENT_NAME = "sstable";
    private static final ValueLayout.OfInt JAVA_INT_BE = ValueLayout.JAVA_INT_UNALIGNED.withOrder(ByteOrder.BIG_ENDIAN);
    private static final ValueLayout.OfLong JAVA_LONG_BE = ValueLayout.JAVA_LONG_UNALIGNED.withOrder(ByteOrder.BIG_ENDIAN);
    
    /**
     * The interval (number of records) at which an entry is added to the sparse index.
     * A smaller interval improves lookup performance but increases index size.
     */
    private static final int INDEX_INTERVAL = 128;

    private final MemoryBudget budget;
    private final String componentName;

    /**
     * Constructs an {@code SstableWriter} with a default component name.
     *
     * @param budget The memory budget for tracking memory-mapped regions.
     */
    public SstableWriter(MemoryBudget budget) {
        this(budget, DEFAULT_COMPONENT_NAME);
    }

    /**
     * Constructs an {@code SstableWriter} with a specific component name.
     *
     * @param budget The memory budget for tracking memory-mapped regions.
     * @param componentName The name used for telemetry and resource tracking.
     */
    public SstableWriter(MemoryBudget budget, String componentName) {
        this.budget = budget;
        this.componentName = componentName;
    }

    /**
     * Flushes the contents of a {@link MemTable} to an SSTable file.
     * <p>
     * This method takes an immutable snapshot of the MemTable and writes all its
     * entries to the specified output path.
     *
     * @param memTable The memtable to flush.
     * @param outputPath The destination path for the new SSTable.
     * @throws StorageException if the flush operation fails.
     */
    public void flush(MemTable memTable, Path outputPath) {
        Map<byte[], byte[]> snapshot = memTable.immutableSnapshot();
        if (snapshot.isEmpty()) return;

        List<SstableIterator.Record> records = snapshot.entrySet().stream()
            .map(e -> new SstableIterator.Record((byte) (e.getValue().length == 0 ? 2 : 1), e.getKey(), e.getValue()))
            .toList();

        writeRecords(records.iterator(), records.size(), outputPath);
    }

    /**
     * Writes a sequence of records to an SSTable file.
     * <p>
     * The writing process involves several phases:
     * 1. Buffering and calculating the total size required for data and index sections.
     * 2. Initializing a temporary memory-mapped file.
     * 3. Writing data records, calculating per-record checksums, and identifying sparse index entries.
     * 4. Writing the sparse index and its footer (offsets array).
     * 5. Finalizing the header with metadata and a global checksum.
     * 6. Atomically moving the temporary file to the final destination.
     *
     * @param records An iterator over the records to write.
     * @param recordCount The total number of records, or -1 if unknown.
     * @param outputPath The final path for the SSTable file.
     * @throws StorageException if an I/O error or checksum calculation fails.
     */
    public void writeRecords(java.util.Iterator<SstableIterator.Record> records, long recordCount, Path outputPath) {
        Path tempPath = outputPath.resolveSibling(outputPath.getFileName() + ".tmp");
        
        List<SstableIterator.Record> buffered = new ArrayList<>();
        long dataSize = 0;
        long actualCount = 0;
        while (records.hasNext()) {
            SstableIterator.Record r = records.next();
            buffered.add(r);
            // 1 (type) + 4 (keyLen) + 4 (valLen) + key + value + 4 (record checksum)
            dataSize += 1 + 8 + r.key().length + r.value().length + 4;
            actualCount++;
        }

        long finalRecordCount = (recordCount >= 0) ? recordCount : actualCount;

        List<IndexEntry> sparseIndex = new ArrayList<>();
        int count = 0;
        long currentDataOffset = 0;

        for (SstableIterator.Record r : buffered) {
            if (count % INDEX_INTERVAL == 0) {
                sparseIndex.add(new IndexEntry(r.key(), SstableHeader.HEADER_SIZE + currentDataOffset));
            }
            // Record size including checksum
            currentDataOffset += 1 + 8 + r.key().length + r.value().length + 4;
            count++;
        }

        long indexEntriesSize = 0;
        for (IndexEntry ie : sparseIndex) {
            indexEntriesSize += 4 + ie.key.length + 8;
        }
        long indexTotalSize = indexEntriesSize + (sparseIndex.size() * 8L) + 8;
        
        long totalFileSize = SstableHeader.HEADER_SIZE + dataSize + indexTotalSize;

        try {
            try (MappedFileWriter writer = new MappedFileWriter(tempPath, totalFileSize, budget, componentName, false)) {
                writer.writeAtCurrentPosition(SstableHeader.HEADER_SIZE, (s, o) -> {}); 

                long dataOffset = writer.getPosition();
                CRC32 globalCrc = new CRC32();

                for (SstableIterator.Record r : buffered) {
                    long payloadSize = 1L + 8L + r.key().length + r.value().length;
                    long totalRecordSize = payloadSize + 4L;

                    writer.writeAtCurrentPosition(totalRecordSize, (segment, offset) -> {
                        long current = offset;
                        segment.set(ValueLayout.JAVA_BYTE, current++, r.type());
                        segment.set(JAVA_INT_BE, current, r.key().length);
                        current += 4;
                        segment.set(JAVA_INT_BE, current, r.value().length);
                        current += 4;

                        MemorySegment.copy(MemorySegment.ofArray(r.key()), 0, segment, current, r.key().length);
                        current += r.key().length;
                        MemorySegment.copy(MemorySegment.ofArray(r.value()), 0, segment, current, r.value().length);
                        current += r.value().length;

                        // Calculate per-record checksum
                        CRC32 recordCrc = new CRC32();
                        updateCrc(recordCrc, segment.asSlice(offset, payloadSize));
                        int checksum = (int) recordCrc.getValue();
                        
                        // Write per-record checksum
                        segment.set(JAVA_INT_BE, current, checksum);

                        // Update global CRC with entire record
                        updateCrc(globalCrc, segment.asSlice(offset, totalRecordSize));
                    });
                }

                long indexStartPos = writer.getPosition();
                long[] relativeEntryPositions = new long[sparseIndex.size()];
                int idx = 0;

                for (IndexEntry ie : sparseIndex) {
                    relativeEntryPositions[idx++] = writer.getPosition() - indexStartPos;
                    long entrySize = 4L + ie.key.length + 8;
                    writer.writeAtCurrentPosition(entrySize, (segment, offset) -> {
                        long current = offset;
                        segment.set(JAVA_INT_BE, current, ie.key.length);
                        current += 4;
                        MemorySegment.copy(MemorySegment.ofArray(ie.key), 0, segment, current, ie.key.length);
                        current += ie.key.length;
                        segment.set(JAVA_LONG_BE, current, ie.offset);
                    });
                }

                // Write relative offsets array (footer)
                writer.writeAtCurrentPosition(relativeEntryPositions.length * 8L, (segment, offset) -> {
                    for (int i = 0; i < relativeEntryPositions.length; i++) {
                        segment.set(JAVA_LONG_BE, offset + (i * 8L), relativeEntryPositions[i]);
                    }
                });

                // Write entry count
                writer.writeAtCurrentPosition(8, (segment, offset) -> {
                    segment.set(JAVA_LONG_BE, offset, (long) relativeEntryPositions.length);
                });

                // Update global CRC with entire index section
                updateCrc(globalCrc, writer.getSegment().asSlice(indexStartPos, writer.getPosition() - indexStartPos));

                SstableHeader header = new SstableHeader(
                    SstableHeader.MAGIC,
                    SstableHeader.VERSION_1,
                    finalRecordCount,
                    indexStartPos, 
                    dataOffset,
                    System.currentTimeMillis(),
                    globalCrc.getValue()
                );

                writer.writeAt(0, SstableHeader.HEADER_SIZE, (segment, offset) -> {
                    SstableHeaderCodec.encode(header, segment, offset);
                });

                writer.force();
            }

            Files.move(tempPath, outputPath, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            try { Files.deleteIfExists(tempPath); } catch (IOException ignored) {}
            throw new StorageException(StorageErrorCode.IO_ERROR, "Failed to flush SSTable: " + outputPath, e);
        } catch (Exception e) {
            try { Files.deleteIfExists(tempPath); } catch (IOException ignored) {}
            throw e;
        }
    }

    /**
     * Estimates the total size of the data section based on a snapshot.
     *
     * @param snapshot The map of keys and values.
     * @return The estimated size in bytes.
     */
    private long calculateDataSize(Map<byte[], byte[]> snapshot) {
        long size = 0;
        for (Map.Entry<byte[], byte[]> entry : snapshot.entrySet()) {
            size += 1 + 8 + entry.getKey().length + entry.getValue().length;
        }
        return size;
    }

    /**
     * Updates the CRC32 checksum with data from a memory segment.
     *
     * @param crc The CRC32 instance to update.
     * @param segment The memory segment containing the data.
     */
    private void updateCrc(CRC32 crc, MemorySegment segment) {
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
     * Internal record representing an entry in the sparse index.
     *
     * @param key The key associated with the index entry.
     * @param offset The absolute offset in the file where the record starts.
     */
    private record IndexEntry(byte[] key, long offset) {}

    /**
     * Closes the writer. Currently a no-op as resources are managed within method scopes.
     *
     * @throws Exception if an error occurs during closing.
     */
    @Override
    public void close() throws Exception {}
}
