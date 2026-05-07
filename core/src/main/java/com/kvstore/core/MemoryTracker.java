package com.kvstore.core;

/**
 * A scoped handle used by a component to track its memory usage against a global {@link MemoryBudget}.
 * <p>
 * This class provides a simplified, component-centric API for memory tracking. Instead of 
 * interacting directly with the global budget and repeatedly providing a component name, 
 * components use this tracker which is pre-configured with their name.
 * <p>
 * Memory tracking is crucial for the stability of the KV-store, especially when using 
 * off-heap memory through the Foreign Function & Memory (FFM) API, as it allows the 
 * system to enforce limits that the JVM heap management cannot see.
 */
public class MemoryTracker {
    /** The unique name of the component using this tracker (e.g., "cache", "memtable"). */
    private final String componentName;
    
    /** The global memory budget that this tracker reports to. */
    private final MemoryBudget budget;

    /**
     * Constructs a {@code MemoryTracker} for a specific component.
     * <p>
     * This constructor is package-private; trackers should be created via 
     * {@link MemoryBudget#registerComponent(String)}.
     *
     * @param componentName the name of the component.
     * @param budget        the global budget to associate with.
     */
    MemoryTracker(String componentName, MemoryBudget budget) {
        this.componentName = componentName;
        this.budget = budget;
    }

    /**
     * Allocates the specified number of bytes from the global budget for this component.
     * <p>
     * This method should be called <i>before</i> any actual memory allocation occurs.
     *
     * @param bytes the number of bytes to allocate.
     * @throws StorageException if the allocation would exceed the global budget limit.
     */
    public void allocate(long bytes) {
        budget.allocate(componentName, bytes);
    }

    /**
     * Deallocates the specified number of bytes from the global budget for this component.
     * <p>
     * This method should be called <i>after</i> the actual memory has been released 
     * or when it's no longer being tracked.
     *
     * @param bytes the number of bytes to release.
     */
    public void deallocate(long bytes) {
        budget.deallocate(componentName, bytes);
    }

    /**
     * Returns the name of the component associated with this tracker.
     *
     * @return the component name.
     */
    public String getComponentName() {
        return componentName;
    }
}
