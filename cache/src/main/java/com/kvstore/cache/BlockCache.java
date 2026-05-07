package com.kvstore.cache;

import java.lang.foreign.MemorySegment;
import java.util.Optional;

/**
 * Defines the contract for an off-heap block cache used to store SSTable data blocks.
 * Implementation should focus on minimizing GC pressure by using off-heap memory
 * and providing fast access to frequently used data blocks.
 * <p>
 * The block cache is a critical component for read performance in the LSM-tree architecture,
 * reducing the need to read data blocks from SSTables on disk. By caching recently accessed
 * blocks in memory, the system can bypass expensive disk I/O and decompression for
 * hot data ranges.
 * </p>
 * <p>
 * Implementations are expected to be thread-safe as they will be accessed concurrently
 * by multiple reader threads. Memory management should be careful to avoid leaks, 
 * especially when dealing with off-heap resources.
 * </p>
 */
public interface BlockCache extends AutoCloseable {
    /**
     * Stores a data block in the cache.
     * <p>
     * If an entry with the same {@code sstableId} and {@code offset} already exists,
     * the implementation may choose to overwrite it or ignore the new entry.
     * The provided byte array is typically copied into off-heap memory.
     * </p>
     *
     * @param sstableId The unique identifier of the SSTable the block belongs to. 
     *                  Must not be null.
     * @param offset    The byte offset within the SSTable where the block is located.
     *                  Must be non-negative.
     * @param data      The raw byte data of the block to be cached. Must not be null.
     * @throws IllegalArgumentException if parameters are invalid.
     */
    void put(String sstableId, long offset, byte[] data);

    /**
     * Retrieves a data block from the cache.
     * <p>
     * If the block is present, it is returned as a {@link MemorySegment}. 
     * Implementations must ensure that the returned segment remains valid 
     * as long as it's needed, or clearly document its lifecycle.
     * </p>
     *
     * @param sstableId The unique identifier of the SSTable.
     * @param offset    The byte offset of the block.
     * @return An {@link Optional} containing a {@link MemorySegment} pointing to the cached data,
     *         or an empty Optional if the block is not present in the cache.
     */
    Optional<MemorySegment> get(String sstableId, long offset);

    /**
     * Returns the current total size of the cache in bytes, including overhead.
     * <p>
     * This value should represent the actual memory footprint, including both 
     * the raw data and any metadata overhead required by the cache implementation.
     * </p>
     *
     * @return The number of bytes currently consumed by the cache.
     */
    long sizeInBytes();

    /**
     * Evicts all entries from the cache and releases associated resources.
     * <p>
     * After calling this method, the cache should be empty, and all allocated 
     * memory (off-heap or otherwise) should be released or returned to the budget.
     * </p>
     */
    void clear();

    /**
     * Closes the cache and releases all associated resources.
     * <p>
     * This method is part of the {@link AutoCloseable} contract. Implementations
     * must ensure that all off-heap memory is properly deallocated and any 
     * background resources are shut down.
     * </p>
     *
     * @throws Exception if an error occurs during closing.
     */
    @Override
    void close() throws Exception;
}
