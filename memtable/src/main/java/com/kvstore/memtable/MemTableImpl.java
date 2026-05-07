package com.kvstore.memtable;

import com.kvstore.core.MemoryBudget;
import com.kvstore.core.StorageErrorCode;
import com.kvstore.core.StorageException;

import com.kvstore.core.util.ByteArrayComparator;
import java.util.Collections;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentSkipListMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * A thread-safe implementation of {@link MemTable} based on a {@link ConcurrentSkipListMap}.
 * 
 * <p>This implementation leverages the lock-free properties of {@code ConcurrentSkipListMap}
 * to provide high concurrency for both read and write operations. Keys are maintained in 
 * lexicographical order using {@link ByteArrayComparator}.</p>
 *
 * <p>Memory management is a critical aspect of this class. Every insertion and deletion 
 * updates a {@link MemoryBudget} tracker. The size estimation includes the raw bytes of 
 * keys and values plus a fixed {@code NODE_OVERHEAD} to account for the object headers, 
 * pointers, and internal skip-list nodes.</p>
 *
 * <p><b>Concurrency Note:</b> While the map operations are atomic, the memory budget 
 * allocation happens in conjunction with the map update. If a budget allocation fails, 
 * the operation will throw an exception, although in this simplified implementation, 
 * the map update might have already occurred (a "leaky" overflow approach).</p>
 */
public class MemTableImpl implements MemTable {
    /**
     * Name used for registration in the {@link MemoryBudget} tracker.
     */
    private static final String COMPONENT_NAME = "memtable";

    /**
     * Estimated memory overhead per entry in the skip-list.
     * This constant accounts for:
     * <ul>
     *   <li>Internal skip-list node object header and pointers.</li>
     *   <li>{@link ValueEntry} record object header.</li>
     *   <li>Array object headers for key and value byte arrays.</li>
     * </ul>
     * While not exact, it provides a consistent heuristic for memory-based flush triggers.
     */
    private static final long NODE_OVERHEAD = 64; 

    /**
     * Internal container to distinguish between a stored value and a tombstone.
     * 
     * @param value       the byte array value (null if tombstone)
     * @param isTombstone true if this entry represents a deletion (tombstone)
     */
    private record ValueEntry(byte[] value, boolean isTombstone) {}

    /**
     * The underlying concurrent sorted map.
     */
    private final ConcurrentSkipListMap<byte[], ValueEntry> map;

    /**
     * The global memory budget tracker.
     */
    private final MemoryBudget budget;

    /**
     * Current approximate size of the memtable in bytes.
     */
    private final AtomicLong currentSize = new AtomicLong(0);

    /**
     * Constructs a new {@code MemTableImpl} with the specified memory budget.
     * Upon construction, it registers itself with the provided {@code MemoryBudget} 
     * using the "memtable" component name.
     *
     * @param budget the {@link MemoryBudget} to track and limit memory consumption
     * @throws NullPointerException if the budget is null
     */
    public MemTableImpl(MemoryBudget budget) {
        this.budget = budget;
        this.map = new ConcurrentSkipListMap<>(ByteArrayComparator.INSTANCE);
        budget.registerComponent(COMPONENT_NAME);
    }

    /**
     * Inserts a key-value pair into the memtable.
     * This operation is thread-safe and updates the memory budget.
     *
     * @param key   the byte array representing the key
     * @param value the byte array representing the value
     * @throws StorageException if the key is null or if the memory budget is exceeded
     */
    @Override
    public void put(byte[] key, byte[] value) {
        update(key, value, false);
    }

    /**
     * Records a deletion marker (tombstone) for the specified key.
     * If the key already had a value in this memtable, it is replaced by the tombstone.
     *
     * @param key the key to delete
     * @throws StorageException if the key is null or if the memory budget is exceeded
     */
    @Override
    public void delete(byte[] key) {
        update(key, null, true);
    }

    /**
     * Internal logic for atomic updates to the map and memory budget accounting.
     * 
     * <p>The logic uses {@link ConcurrentSkipListMap#compute(Object, java.util.function.BiFunction)} 
     * to ensure that the calculation of the memory delta (new size minus old size) 
     * is consistent with the state of the map. This prevents race conditions where 
     * two threads updating the same key could result in incorrect memory tracking.</p>
     * 
     * <p><b>Memory Budget Policy:</b> After the map is updated, the memory budget 
     * is adjusted. If {@link MemoryBudget#allocate(String, long)} fails (throws), 
     * the memtable size tracker is still updated, but the caller receives the error, 
     * which typically signals that a flush is required.</p>
     *
     * @param key         the key to update
     * @param value       the value associated with the key (null if tombstone)
     * @param isTombstone true if this is a deletion operation
     * @throws StorageException if the key is null or if the budget allocation fails
     */
    private void update(byte[] key, byte[] value, boolean isTombstone) {
        if (key == null) throw new StorageException(StorageErrorCode.INVALID_CONFIGURATION, "Key cannot be null");
        
        ValueEntry newEntry = new ValueEntry(value, isTombstone);
        long newEntrySize = calculateEntrySize(key, newEntry);
        AtomicLong deltaTracker = new AtomicLong(0);

        map.compute(key, (k, oldEntry) -> {
            long delta = newEntrySize;
            if (oldEntry != null) {
                delta -= calculateEntrySize(k, oldEntry);
            }
            
            deltaTracker.set(delta);
            return newEntry;
        });

        long delta = deltaTracker.get();
        if (delta > 0) {
            budget.allocate(COMPONENT_NAME, delta);
        } else if (delta < 0) {
            budget.deallocate(COMPONENT_NAME, -delta);
        }
        currentSize.addAndGet(delta);
    }

    /**
     * Searches for a key in this memtable and returns its value if found.
     * Returns {@link Optional#empty()} if the key is not present or if it is 
     * marked as deleted (tombstone).
     *
     * @param key the key to look up
     * @return an {@link Optional} containing the value, or empty if missing or deleted
     */
    @Override
    public Optional<byte[]> get(byte[] key) {
        if (key == null) return Optional.empty();
        ValueEntry entry = map.get(key);
        if (entry == null || entry.isTombstone()) {
            return Optional.empty();
        }
        return Optional.of(entry.value());
    }

    /**
     * Returns the total estimated memory footprint of this memtable.
     * This value is used by the storage engine to decide when to flush 
     * the memtable to disk.
     *
     * @return approximate memory usage in bytes
     */
    @Override
    public long sizeInBytes() {
        return currentSize.get();
    }

    /**
     * Generates a sorted, immutable view of the memtable's content.
     * 
     * <p>This method converts internal {@link ValueEntry} objects into a simple 
     * map of byte arrays. Tombstones are converted to empty byte arrays (length 0) 
     * so they can be written to the SSTable and correctly processed by the 
     * reader and compaction components.</p>
     *
     * @return an unmodifiable sorted map of all current entries
     */
    @Override
    public Map<byte[], byte[]> immutableSnapshot() {
        // Use TreeMap to ensure results are sorted even though CSLM is already sorted,
        // this provides an extra layer of consistency for the snapshot.
        Map<byte[], byte[]> snapshot = new TreeMap<>(ByteArrayComparator.INSTANCE);
        for (Map.Entry<byte[], ValueEntry> entry : map.entrySet()) {
            byte[] val = entry.getValue().isTombstone() ? new byte[0] : entry.getValue().value();
            snapshot.put(entry.getKey(), val);
        }
        return Collections.unmodifiableMap(snapshot);
    }

    /**
     * Atomically clears the memtable and notifies the memory budget.
     * This is usually called after a successful flush to disk has completed.
     */
    @Override
    public synchronized void clear() {
        long size = currentSize.getAndSet(0);
        map.clear();
        if (size > 0) {
            budget.deallocate(COMPONENT_NAME, size);
        }
    }

    /**
     * Closes the memtable, effectively clearing its content and releasing its 
     * portion of the memory budget.
     */
    @Override
    public void close() {
        clear();
    }

    /**
     * Estimates the memory footprint of a single entry.
     * 
     * <p>Includes the fixed structural overhead plus the actual length of 
     * the key and value byte arrays.</p>
     *
     * @param key   the byte array key
     * @param entry the value container
     * @return estimated memory consumption in bytes
     */
    private long calculateEntrySize(byte[] key, ValueEntry entry) {
        long keyLen = (key == null) ? 0 : key.length;
        long valLen = (entry == null || entry.value() == null) ? 0 : entry.value().length;
        return NODE_OVERHEAD + keyLen + valLen;
    }
}
