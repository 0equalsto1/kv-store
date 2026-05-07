package com.kvstore.concurrency;

import java.util.concurrent.atomic.AtomicLong;

/**
 * A concrete implementation of {@link BackpressureSignal} that uses compaction lag
 * as the primary metric for applying write backpressure.
 * <p>
 * This implementation uses a simple threshold-based approach: if the reported
 * compaction lag exceeds a configured threshold, {@link #canWrite()} will return
 * {@code false}, and {@link #waitForCapacity()} will block the writer.
 * </p>
 * <p>
 * This mechanism ensures that the foreground write throughput is throttled
 * to match the background compaction throughput when the system is under
 * heavy load, maintaining the structural integrity of the LSM-tree.
 * </p>
 */
public class WriteBackpressure implements BackpressureSignal {
    /** The threshold in milliseconds beyond which backpressure is applied. */
    private final long lagThresholdMillis;
    
    /** The current compaction lag being tracked, in milliseconds. */
    private final AtomicLong compactionLagMillis = new AtomicLong(0);

    /**
     * Constructs a new WriteBackpressure instance with a specific lag threshold.
     *
     * @param lagThresholdMillis The maximum allowed compaction lag in milliseconds 
     *                           before backpressure is applied. Must be positive.
     */
    public WriteBackpressure(long lagThresholdMillis) {
        this.lagThresholdMillis = lagThresholdMillis;
    }

    /**
     * {@inheritDoc}
     * <p>
     * Writes are allowed if the current {@code compactionLagMillis} is strictly
     * less than the {@code lagThresholdMillis}.
     * </p>
     *
     * @return {@code true} if lag is within limits, {@code false} otherwise.
     */
    @Override
    public boolean canWrite() {
        return compactionLagMillis.get() < lagThresholdMillis;
    }

    /**
     * {@inheritDoc}
     * <p>
     * Blocks the current thread using a polling loop with a short sleep.
     * If the JVM is using Project Loom (Virtual Threads), {@link Thread#sleep(long)}
     * is highly efficient and will not block a carrier thread.
     * </p>
     *
     * @throws InterruptedException if the thread is interrupted while waiting.
     */
    @Override
    public void waitForCapacity() throws InterruptedException {
        while (!canWrite()) {
            // Virtual thread parks cheaply; no platform thread starvation.
            // A 10ms sleep provides a good balance between responsiveness and CPU usage.
            Thread.sleep(10);
        }
    }

    /**
     * {@inheritDoc}
     * <p>
     * Updates the internal lag counter. This will immediately affect the result
     * of subsequent {@link #canWrite()} calls.
     * </p>
     *
     * @param lagMillis The reported compaction lag in milliseconds.
     */
    @Override
    public void signalCompactionLag(long lagMillis) {
        compactionLagMillis.set(lagMillis);
    }

    /**
     * {@inheritDoc}
     * <p>
     * Resets the compaction lag to zero, effectively clearing any active backpressure.
     * This simple implementation assumes that completion signals a return to a 
     * healthy state.
     * </p>
     */
    @Override
    public void signalCompactionComplete() {
        // Simple implementation: completion resets lag.
        // In a more complex system, this might transition between lag levels
        // or decrement based on the size of the compacted data.
        compactionLagMillis.set(0);
    }

    /**
     * Returns the current compaction lag being tracked by this signal.
     * <p>
     * This method is useful for monitoring and telemetry purposes to observe
     * how close the system is to triggering backpressure.
     * </p>
     *
     * @return The current lag in milliseconds.
     */
    public long getCurrentLag() {
        return compactionLagMillis.get();
    }
}
