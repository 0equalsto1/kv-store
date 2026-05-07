package com.kvstore.wal;

import com.kvstore.core.MemoryBudget;
import com.kvstore.io.MappedFileWriter;

import java.nio.file.Path;

/**
 * Responsible for writing operations to the Write-Ahead Log (WAL).
 * <p>
 * This class provides a thread-safe interface for appending {@link WalRecord}s
 * to a memory-mapped log file. It ensures that operations are persisted
 * sequentially and provides methods for forcing data to disk to guarantee durability.
 */
public class WalWriter implements AutoCloseable {
    private final MappedFileWriter writer;
    private final Path path;

    /**
     * Constructs a {@code WalWriter} for the specified file and size.
     *
     * @param path The path where the WAL file is located.
     * @param size The maximum size of the WAL file.
     * @param budget The memory budget for tracking memory-mapped regions.
     */
    public WalWriter(Path path, long size, MemoryBudget budget) {
        this.path = path;
        // Correctly attribute to "wal" component
        this.writer = new MappedFileWriter(path, size, budget, "wal");
    }

    /**
     * Appends a PUT operation to the log.
     * <p>
     * This is a convenience method that wraps the key and value into a
     * {@link WalRecord} with {@link WalRecord#TYPE_PUT}.
     *
     * @param key The key to be inserted or updated.
     * @param value The value associated with the key.
     */
    public synchronized void appendPut(byte[] key, byte[] value) {
        append(new WalRecord(WalRecord.VERSION_1, WalRecord.TYPE_PUT, key, value, 0));
    }

    /**
     * Appends a DELETE operation to the log.
     * <p>
     * This is a convenience method that wraps the key into a
     * {@link WalRecord} with {@link WalRecord#TYPE_DELETE}.
     *
     * @param key The key to be deleted.
     */
    public synchronized void appendDelete(byte[] key) {
        append(new WalRecord(WalRecord.VERSION_1, WalRecord.TYPE_DELETE, key, new byte[0], 0));
    }

    /**
     * Appends a {@link WalRecord} to the log.
     * <p>
     * This method uses zero-copy encoding, serializing the record directly into
     * the memory-mapped segment at the current position.
     *
     * @param record The record to append.
     */
    public synchronized void append(WalRecord record) {
        long size = WalRecordCodec.calculateSize(record);
        // Zero-copy: Encode directly into the mapped memory segment
        writer.writeAtCurrentPosition(size, (segment, offset) -> {
            WalRecordCodec.encode(record, segment, offset);
        });
    }

    /**
     * Forces all appended data to be persisted to the underlying storage device.
     * <p>
     * This method should be called after critical operations to ensure durability.
     */
    public synchronized void force() {
        writer.force();
    }

    /**
     * Returns the path to the WAL file.
     *
     * @return The file path.
     */
    public Path getPath() {
        return path;
    }

    /**
     * Closes the writer and releases associated memory-mapped resources.
     */
    @Override
    public void close() {
        writer.close();
    }
    
    /**
     * Returns the current write position (offset) in the log file.
     *
     * @return The current position in bytes.
     */
    public long getPosition() {
        return writer.getPosition();
    }
}
