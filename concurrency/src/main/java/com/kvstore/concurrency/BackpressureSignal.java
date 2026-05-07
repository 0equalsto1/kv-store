package com.kvstore.concurrency;

/**
 * Coordination interface between background maintenance processes (like compaction) 
 * and foreground write operations.
 * <p>
 * In an LSM-tree, if background compaction cannot keep up with the write rate,
 * the number of SSTables can grow uncontrollably, leading to "read amplification"
 * and eventual system instability. This interface allows the compaction scheduler
 * to signal when it is falling behind, allowing the system to apply backpressure
 * by slowing down or blocking foreground writers.
 * </p>
 * <p>
 * Backpressure is a critical stability mechanism that prevents the engine from
 * accepting more data than it can realistically process and organize. Without
 * backpressure, a burst of writes could lead to an unmanageable number of 
 * overlapping SSTables, severely degrading read performance.
 * </p>
 */
public interface BackpressureSignal {
    /**
     * Checks if foreground write operations are currently allowed to proceed without delay.
     * <p>
     * This is a non-blocking check that foreground writers can use to determine
     * if they should proceed or call {@link #waitForCapacity()}.
     * </p>
     *
     * @return {@code true} if writes are allowed; {@code false} if backpressure should be applied.
     */
    boolean canWrite();

    /**
     * Blocks the calling thread until the system has enough capacity to accept more writes.
     * <p>
     * Implementation should be efficient, ideally parking the thread if it's a virtual thread
     * to avoid platform thread starvation. This method is called by foreground 
     * writer threads when {@link #canWrite()} returns {@code false}.
     * </p>
     *
     * @throws InterruptedException if the thread is interrupted while waiting.
     */
    void waitForCapacity() throws InterruptedException;

    /**
     * Signals that background maintenance is lagging behind the desired state.
     * <p>
     * This method is called by the compaction engine to update the current
     * perceived lag. The signal implementation uses this lag to decide 
     * whether to trigger backpressure.
     * </p>
     *
     * @param lagMillis The current maintenance lag measured in milliseconds.
     *                  A higher value typically indicates a greater need for backpressure.
     */
    void signalCompactionLag(long lagMillis);

    /**
     * Signals that a maintenance operation has completed, potentially easing backpressure.
     * <p>
     * This method is called by the compaction engine after a successful
     * compaction task. It allows the backpressure signal to re-evaluate 
     * the system state and potentially unblock waiting writers.
     * </p>
     */
    void signalCompactionComplete();
}
