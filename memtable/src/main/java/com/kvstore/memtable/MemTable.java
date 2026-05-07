package com.kvstore.memtable;

import com.kvstore.core.StorageException;
import java.util.Map;
import java.util.Optional;

/**
 * An in-memory buffer that stores active writes before they are flushed to SSTables.
 * The {@code MemTable} serves as the first point of entry for all write operations (puts and deletes)
 * in an LSM-tree (Log-Structured Merge-Tree) storage engine. It provides fast, sorted access 
 * to the most recently written data, acting as a write-back cache.
 *
 * <p>Key characteristics of a {@code MemTable}:
 * <ul>
 *   <li><b>Sorted:</b> Entries are maintained in sorted order by key to facilitate efficient 
 *       range scans and the eventual creation of sorted SSTable files.</li>
 *   <li><b>Thread-Safe:</b> Implementations must support concurrent access from multiple 
 *       application threads for writes and reads, as well as background flush threads.</li>
 *   <li><b>Size-Limited:</b> Once the memory usage exceeds a configured threshold, the 
 *       {@code MemTable} is typically marked as immutable and replaced with a new instance, 
 *       while the old one is flushed to disk.</li>
 * </ul>
 *
 * @see com.kvstore.memtable.MemTableImpl
 */
public interface MemTable extends AutoCloseable {
    /**
     * Inserts or updates a key-value pair in the memtable.
     * If the key already exists, the new value replaces the old one, and the memory
     * usage tracking is adjusted accordingly.
     *
     * @param key   the byte array representing the key; must not be null
     * @param value the byte array representing the value; must not be null
     * @throws StorageException if memory allocation fails or if the key is invalid
     */
    void put(byte[] key, byte[] value);

    /**
     * Marks a key as deleted by inserting a tombstone entry.
     * In an LSM-tree, deletions are handled by inserting a special marker called a "tombstone" 
     * rather than immediately removing the data from disk, which allows the deletion to 
     * propagate during subsequent compactions.
     *
     * @param key the byte array representing the key to be deleted; must not be null
     * @throws StorageException if memory allocation fails or if the key is invalid
     */
    void delete(byte[] key);

    /**
     * Retrieves the most recent value associated with the given key from this memtable.
     * This method only searches the current memtable and does not check on-disk SSTables.
     *
     * @param key the byte array representing the key to search for
     * @return an {@link Optional} containing the value if present and not deleted,
     *         or an empty {@code Optional} if the key is missing or marked with a tombstone
     */
    Optional<byte[]> get(byte[] key);

    /**
     * Returns the approximate memory usage of this memtable in bytes.
     * This include the memory consumed by the keys, values, and the structural overhead 
     * of the internal data structure (e.g., skip-list node overhead).
     *
     * @return the estimated size in bytes currently occupied by this memtable
     */
    long sizeInBytes();

    /**
     * Creates an immutable, sorted snapshot of the current state of the memtable.
     * This method is typically called by a background flush thread to obtain a stable 
     * view of the data to be written to a new SSTable file.
     *
     * <p>Tombstones are represented in the snapshot by a specific marker (typically an 
     * empty byte array) to ensure they are persisted and can correctly mask older values 
     * in existing SSTables during read operations and compactions.</p>
     *
     * @return a sorted map representing the current state, including tombstones
     */
    Map<byte[], byte[]> immutableSnapshot();

    /**
     * Clears all entries from the memtable and resets its memory usage tracker.
     * This method also releases any associated memory budget from the global tracker.
     */
    void clear();

    /**
     * Closes the memtable and releases all associated resources.
     * Implementation should ensure that any registered memory budget is deallocated.
     *
     * @throws Exception if an error occurs during closing
     */
    @Override
    void close() throws Exception;
}
