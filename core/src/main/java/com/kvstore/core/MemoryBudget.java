package com.kvstore.core;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Manages and enforces memory allocation limits across different components of the storage engine.
 * <p>
 * This class acts as a centralized authority for memory usage. Components must register 
 * themselves and request allocations before consuming memory (especially off-heap).
 * It ensures that the total memory consumption does not exceed the configured global limit.
 * <p>
 * Thread Safety: This class is fully thread-safe, using {@link AtomicLong} for total 
 * usage tracking and a {@link ConcurrentHashMap} to manage per-component trackers.
 */
public class MemoryBudget {
    /** The maximum allowed bytes for the entire engine. */
    private final long maxBytesAllowed;
    
    /** The current total bytes allocated across all components. */
    private final AtomicLong bytesUsed = new AtomicLong(0);
    
    /** Map tracking memory usage per component name. */
    private final Map<String, AtomicLong> componentUsage = new ConcurrentHashMap<>();

    /**
     * Constructs a {@code MemoryBudget} with a specified global limit.
     *
     * @param maxBytesAllowed the maximum number of bytes allowed to be allocated.
     *                       Must be a positive value.
     */
    public MemoryBudget(long maxBytesAllowed) {
        this.maxBytesAllowed = maxBytesAllowed;
    }

    /**
     * Registers a component with the budget and returns a scoped {@link MemoryTracker}.
     * <p>
     * If a component with the same name is already registered, this returns a new 
     * tracker pointing to the existing usage counter.
     *
     * @param componentName the unique name of the component (e.g., "cache-0").
     * @return a {@link MemoryTracker} instance scoped to the provided name.
     */
    public MemoryTracker registerComponent(String componentName) {
        componentUsage.computeIfAbsent(componentName, k -> new AtomicLong(0));
        return new MemoryTracker(componentName, this);
    }

    /**
     * Attempts to allocate memory for a specific component.
     * <p>
     * This method uses an optimistic update pattern: it first adds to the total usage,
     * checks if the limit is exceeded, and if so, rolls back the change and throws
     * an exception. This ensures that the global limit is strictly enforced even 
     * under high concurrency.
     *
     * @param componentName the name of the component requesting memory.
     * @param bytes         the number of bytes to allocate.
     * @throws StorageException if the allocation would exceed the global budget limit
     *                           or if the component has not been registered.
     */
    public void allocate(String componentName, long bytes) {
        if (bytes < 0) {
            throw new StorageException(StorageErrorCode.INVALID_CONFIGURATION,
                "Cannot allocate negative bytes: " + bytes);
        }
        if (bytes == 0) return;

        AtomicLong tracker = componentUsage.get(componentName);
        if (tracker == null) {
            throw new StorageException(StorageErrorCode.INVALID_CONFIGURATION,
                "Component not registered: " + componentName);
        }

        long total = bytesUsed.addAndGet(bytes);
        if (total > maxBytesAllowed) {
            // Roll back the allocation if it exceeds the limit
            bytesUsed.addAndGet(-bytes);
            throw new StorageException(StorageErrorCode.BUDGET_EXCEEDED,
                String.format("Budget exceeded: %d / %d bytes (requested %d)", total, maxBytesAllowed, bytes));
        }
        tracker.addAndGet(bytes);
    }

    /**
     * Deallocates memory previously reserved for a component.
     *
     * @param componentName the name of the component releasing memory.
     * @param bytes         the number of bytes to release.
     * @throws StorageException if {@code bytes} is negative.
     */
    public void deallocate(String componentName, long bytes) {
        if (bytes < 0) {
            throw new StorageException(StorageErrorCode.INVALID_CONFIGURATION,
                "Cannot deallocate negative bytes: " + bytes);
        }
        if (bytes == 0) return;

        AtomicLong tracker = componentUsage.get(componentName);
        if (tracker != null) {
            tracker.addAndGet(-bytes);
        }
        bytesUsed.addAndGet(-bytes);
    }

    /**
     * Returns the total number of bytes currently allocated across all components.
     *
     * @return current total memory usage in bytes.
     */
    public long getBytesUsed() {
        return bytesUsed.get();
    }

    /**
     * Returns the memory usage for a specific component.
     *
     * @param componentName the name of the component to query.
     * @return the number of bytes currently allocated by the specified component.
     */
    public long getComponentUsage(String componentName) {
        AtomicLong tracker = componentUsage.get(componentName);
        return tracker != null ? tracker.get() : 0;
    }

    /**
     * Returns the maximum global memory limit configured for this engine instance.
     *
     * @return the maximum allowed bytes.
     */
    public long getMaxBytesAllowed() {
        return maxBytesAllowed;
    }
}
