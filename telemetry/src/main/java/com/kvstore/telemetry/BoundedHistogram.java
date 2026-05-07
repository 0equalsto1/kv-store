package com.kvstore.telemetry;

import java.util.Arrays;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLongArray;

/**
 * A bounded histogram that stores a fixed number of samples and calculates percentiles.
 * <p>
 * Thread-safe for recording using {@link AtomicLongArray} to prevent word tearing and ensure visibility.
 * This is used to track latency distributions for read and write operations without requiring
 * an unbounded amount of memory.
 * </p>
 * <p>
 * When the capacity is reached, new samples overwrite the oldest ones in a circular fashion.
 * This provides a sliding window effect where only the most recent samples are considered
 * for percentile calculations.
 * </p>
 */
public class BoundedHistogram {
    /** The thread-safe array for storing samples. */
    private final AtomicLongArray samples;
    
    /** The maximum number of samples to retain. */
    private final int capacity;
    
    /** The current insertion position in the circular buffer. */
    private final AtomicInteger position = new AtomicInteger(0);
    
    /** The total number of samples recorded, capped at capacity. */
    private final AtomicInteger count = new AtomicInteger(0);

    /**
     * Constructs a BoundedHistogram with a fixed capacity.
     *
     * @param capacity The maximum number of samples to retain. Must be positive.
     */
    public BoundedHistogram(int capacity) {
        this.capacity = capacity;
        this.samples = new AtomicLongArray(capacity);
    }

    /**
     * Records a new value in the histogram.
     * <p>
     * Overwrites the oldest value if the capacity is reached. This operation
     * is wait-free for recorders, as it uses atomic increments and stores.
     * </p>
     *
     * @param value The value to record (e.g., latency in milliseconds).
     */
    public void record(long value) {
        // Use bitwise mask with Integer.MAX_VALUE to handle overflow of position 
        // increment while ensuring a positive index for the modulo operation.
        int pos = (position.getAndIncrement() & Integer.MAX_VALUE) % capacity;
        samples.set(pos, value);
        
        // Atomically update count if we haven't reached capacity yet.
        if (count.get() < capacity) {
            count.incrementAndGet();
        }
    }

    /**
     * Calculates the specified percentile of the recorded values.
     * <p>
     * This operation takes a snapshot of the current samples and sorts them,
     * so it may be expensive (O(N log N)) for large capacities. It is synchronized
     * to prevent concurrent snapshots from interfering with each other, though
     * recorders can still proceed.
     * </p>
     *
     * @param percentile The percentile to calculate (e.g., 50.0 for median, 99.0 for P99).
     *                   Must be between 0 and 100.
     * @return The value at the specified percentile, or 0 if no samples have been recorded.
     */
    public synchronized long getPercentile(double percentile) {
        int currentCount = Math.min(count.get(), capacity);
        if (currentCount == 0) {
            return 0;
        }

        // Snapshot current values into a primitive array for sorting.
        long[] snapshot = new long[currentCount];
        for (int i = 0; i < currentCount; i++) {
            snapshot[i] = samples.get(i);
        }
        Arrays.sort(snapshot);

        if (percentile <= 0) return snapshot[0];
        if (percentile >= 100) return snapshot[currentCount - 1];

        // Nearest rank calculation.
        int index = (int) Math.ceil(percentile / 100.0 * currentCount) - 1;
        return snapshot[Math.max(0, index)];
    }

    /**
     * Clears all samples from the histogram.
     * <p>
     * Resets the position and count to zero, and wipes the underlying array.
     * This is an expensive operation and is synchronized.
     * </p>
     */
    public synchronized void clear() {
        position.set(0);
        count.set(0);
        for (int i = 0; i < capacity; i++) {
            samples.set(i, 0);
        }
    }
}
