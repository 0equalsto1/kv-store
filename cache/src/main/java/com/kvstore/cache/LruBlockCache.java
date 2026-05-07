package com.kvstore.cache;

import com.kvstore.core.MemoryBudget;
import com.kvstore.core.StorageErrorCode;
import com.kvstore.core.StorageException;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;

/**
 * An LRU (Least Recently Used) implementation of {@link BlockCache} that utilizes
 * the Foreign Function & Memory (FFM) API for off-heap storage.
 * <p>
 * This implementation enforces a maximum memory limit and integrates with the
 * {@link MemoryBudget} to ensure system-wide memory constraints are respected.
 * It uses a {@link LinkedHashMap} with access ordering to track and evict
 * the least recently used blocks when the cache reaches its capacity.
 * </p>
 * <p>
 * Off-heap memory is managed using a shared {@link Arena}. All allocations are
 * freed when the cache is closed. Individual evictions reduce the reported size
 * and return the budget, but the underlying off-heap memory in the shared arena
 * might not be immediately reclaimed by the OS until the arena is closed.
 * </p>
 */
public class LruBlockCache implements BlockCache {
    /** The name of the component for memory budget tracking. */
    private static final String COMPONENT_NAME = "cache";
    
    /** 
     * Estimated overhead per entry in bytes for metadata and map nodes. 
     * This includes the {@link CacheKey} and {@link CacheEntry} objects, 
     * and the {@link LinkedHashMap.Entry} nodes.
     */
    private static final long OVERHEAD_PER_ENTRY = 128; 

    /** The maximum number of bytes this cache is allowed to consume. */
    private final long maxBytes;
    
    /** The global memory budget tracker. */
    private final MemoryBudget budget;
    
    /** The off-heap memory arena. */
    private final Arena arena;
    
    /** The underlying map for storing cache entries with LRU eviction. */
    private final Map<CacheKey, CacheEntry> map;
    
    /** Lock for synchronizing access to the map and currentBytes. */
    private final Lock lock = new ReentrantLock();
    
    /** The current number of bytes consumed by the cache, including overhead. */
    private final AtomicLong currentBytes = new AtomicLong(0);

    /**
     * Constructs a new LruBlockCache with the specified memory limit.
     *
     * @param maxBytes The maximum amount of memory in bytes this cache is allowed to use.
     * @param budget   The global memory budget to register with and allocate from.
     */
    public LruBlockCache(long maxBytes, MemoryBudget budget) {
        this.maxBytes = maxBytes;
        this.budget = budget;
        this.arena = Arena.ofShared();
        budget.registerComponent(COMPONENT_NAME);
        
        // LRU implementation using LinkedHashMap with accessOrder=true.
        // The removeEldestEntry hook is triggered by put() and putAll().
        this.map = new LinkedHashMap<>(1024, 0.75f, true) {
            @Override
            protected boolean removeEldestEntry(Map.Entry<CacheKey, CacheEntry> eldest) {
                // If we are over capacity, evict the oldest entry.
                if (currentBytes.get() > LruBlockCache.this.maxBytes) {
                    evict(eldest.getKey(), eldest.getValue());
                    return true;
                }
                return false;
            }
        };
    }

    /**
     * Unique key for a cached block, consisting of the SSTable ID and the byte offset.
     * 
     * @param sstableId The identifier of the SSTable.
     * @param offset    The byte offset within the file.
     */
    private record CacheKey(String sstableId, long offset) {}
    
    /**
     * Wrapper for a cached off-heap segment and its size (including metadata).
     * 
     * @param segment The off-heap memory segment.
     * @param size    The total size in bytes attributed to this entry.
     */
    private record CacheEntry(MemorySegment segment, long size) {}

    /**
     * Stores a data block in the off-heap cache.
     * <p>
     * This implementation first checks if the entry already exists. If not, it attempts
     * to allocate the required memory from the {@link MemoryBudget}. If the budget
     * check passes, it allocates an off-heap {@link MemorySegment} and copies the
     * data into it. Finally, it adds the entry to the map, which may trigger
     * the eviction of the least recently used entry if the cache size exceeds {@code maxBytes}.
     * </p>
     *
     * @param sstableId The unique identifier of the SSTable.
     * @param offset    The byte offset of the block.
     * @param data      The block data to cache.
     * @throws com.kvstore.core.StorageException if the memory budget is exceeded.
     */
    @Override
    public void put(String sstableId, long offset, byte[] data) {
        long size = data.length + OVERHEAD_PER_ENTRY;
        CacheKey key = new CacheKey(sstableId, offset);
        
        lock.lock();
        try {
            // Check if already exists to avoid double allocation and potential leaks in shared arena.
            if (map.containsKey(key)) return;

            // Allocate from budget first. This ensures we don't exceed global limits.
            budget.allocate(COMPONENT_NAME, size);
            
            // Allocate off-heap memory and copy the data.
            MemorySegment segment = arena.allocate(data.length);
            MemorySegment.copy(MemorySegment.ofArray(data), 0, segment, 0, data.length);
            
            currentBytes.addAndGet(size);
            map.put(key, new CacheEntry(segment, size));
            
            // Trigger evictions if needed (LinkedHashMap.put calls removeEldestEntry)
        } catch (Exception e) {
            // Re-throw if it's already a StorageException or wrap it.
            if (e instanceof RuntimeException re) throw re;
            throw new RuntimeException("Failed to put block in cache", e);
        } finally {
            lock.unlock();
        }
    }

    /**
     * Retrieves a block from the cache.
     * <p>
     * Calling this method updates the access order of the entry in the underlying
     * {@link LinkedHashMap}, moving it to the "most recently used" position.
     * </p>
     *
     * @param sstableId The unique identifier of the SSTable.
     * @param offset    The byte offset of the block.
     * @return An {@link Optional} containing the {@link MemorySegment}, or empty if not found.
     */
    @Override
    public Optional<MemorySegment> get(String sstableId, long offset) {
        CacheKey key = new CacheKey(sstableId, offset);
        lock.lock();
        try {
            // get() on LinkedHashMap with accessOrder=true updates the LRU order.
            CacheEntry entry = map.get(key);
            return Optional.ofNullable(entry).map(CacheEntry::segment);
        } finally {
            lock.unlock();
        }
    }

    /**
     * Evicts a specific entry from the cache and releases its memory back to the budget.
     * <p>
     * This is an internal helper called by the {@link LinkedHashMap} when an entry
     * needs to be removed due to the LRU policy. It decrements the current byte count
     * and signals the {@link MemoryBudget} that the memory is no longer in use.
     * </p>
     *
     * @param key   The cache key to evict.
     * @param entry The cache entry containing the memory segment and size.
     */
    private void evict(CacheKey key, CacheEntry entry) {
        // This is called while the lock is held (inside map.put)
        currentBytes.addAndGet(-entry.size());
        budget.deallocate(COMPONENT_NAME, entry.size());
        
        // Note: The memory segment remains allocated within the shared arena.
        // In a high-churn environment, this implementation might lead to off-heap 
        // fragmentation or high residency until the cache is closed.
    }

    /**
     * Returns the current total size of the cache in bytes.
     *
     * @return The number of bytes currently tracked by the cache, including metadata overhead.
     */
    @Override
    public long sizeInBytes() {
        return currentBytes.get();
    }

    /**
     * Clears all entries from the cache and releases the corresponding memory budget.
     * <p>
     * This method removes all entries from the map and returns all tracked bytes
     * to the {@link MemoryBudget}. However, it does not close the {@link Arena},
     * so the off-heap memory might still be held by the JVM until {@link #close()} is called.
     * </p>
     */
    @Override
    public void clear() {
        lock.lock();
        try {
            long size = currentBytes.get();
            map.clear();
            budget.deallocate(COMPONENT_NAME, size);
            currentBytes.set(0);
        } finally {
            lock.unlock();
        }
    }

    /**
     * Closes the cache and releases all off-heap memory back to the system.
     * <p>
     * This implementation closes the {@link Arena}, which immediately deallocates 
     * all off-heap segments allocated by this cache. It also releases the 
     * remaining memory budget associated with the cache component.
     * </p>
     */
    @Override
    public void close() {
        arena.close(); // Frees all off-heap memory associated with this arena.
        budget.deallocate(COMPONENT_NAME, currentBytes.get());
    }
}
