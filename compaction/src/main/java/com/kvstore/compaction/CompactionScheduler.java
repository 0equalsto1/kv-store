package com.kvstore.compaction;

import com.kvstore.sstable.SstableReader;
import com.kvstore.concurrency.BackpressureSignal;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.logging.Logger;

/**
 * Monitors the storage state and orchestrates background compaction tasks.
 * <p>
 * The scheduler periodically checks the number of SSTables and triggers compaction
 * when a configured threshold is reached. It serves as the "brain" of the 
 * maintenance subsystem, ensuring that the system remains responsive and 
 * storage-efficient by managing read amplification and reclaiming space.
 * <p>
 * Key Responsibilities:
 * <ul>
 *     <li>Periodically monitoring SSTable counts via a scheduled executor.</li>
 *     <li>Identifying candidates for compaction.</li>
 *     <li>Enforcing write backpressure when maintenance falls behind.</li>
 *     <li>Managing a worker pool for executing {@link CompactionTask}s.</li>
 * </ul>
 * <p>
 * Threading Model: Uses a single-thread scheduled executor for monitoring 
 * and a {@link ForkJoinPool} for performing the actual compaction work, 
 * aligning with Architecture Pattern 5.
 */
public class CompactionScheduler implements AutoCloseable {
    /** Logger for tracking scheduling decisions and worker status. */
    private static final Logger logger = Logger.getLogger(CompactionScheduler.class.getName());

    /** The engine used to perform the heavy-duty merge operations. */
    private final CompactionEngine engine;
    /** The base directory where SSTable files are stored. */
    private final Path storageDir;
    /** The number of SSTables that, when reached, triggers a compaction run. */
    private final int fileCountThreshold;
    /** Scheduled executor that runs the periodic monitoring task. */
    private final ScheduledExecutorService monitor;
    /** Worker pool for running compaction tasks. Using ForkJoinPool for efficient CPU utilization. */
    private final ExecutorService worker;
    /** Guard to prevent multiple concurrent compactions from being scheduled simultaneously. */
    private final AtomicBoolean compactionInProgress = new AtomicBoolean(false);
    /** Component that provides access to the current state of SSTables. */
    private final SstableProvider sstableProvider;
    /** Timestamp of when the system first entered a "compaction needed" state, used for lag calculation. */
    private long pendingSince = 0;

    /**
     * Interface for the storage engine to provide candidate SSTables and handle compaction results.
     * <p>
     * This decoupling allows the scheduler to remain agnostic of the specific 
     * storage engine implementation details.
     */
    public interface SstableProvider {
        /**
         * Returns a list of SSTables that are currently eligible for compaction.
         *
         * @return a list of candidate {@link SstableReader}s, ordered from NEWEST to OLDEST.
         */
        List<SstableReader> getSstablesForCompaction();

        /**
         * Returns the total number of SSTables currently managed by the engine.
         *
         * @return the total SSTable count.
         */
        int getTotalSstableCount();

        /**
         * Callback invoked when a compaction operation has successfully finished.
         * <p>
         * The implementation should atomically swap the old SSTables for the new 
         * one in the engine's active set.
         *
         * @param newSstable  the path to the newly created compacted SSTable.
         * @param oldSstables the list of SSTables that were merged and are now obsolete.
         */
        void onCompactionFinished(Path newSstable, List<SstableReader> oldSstables);

        /**
         * Provides access to the backpressure signal used to report maintenance lag 
         * to foreground writers.
         *
         * @return the {@link BackpressureSignal} instance.
         */
        BackpressureSignal getBackpressureSignal();
    }

    /**
     * Constructs a {@code CompactionScheduler}.
     *
     * @param engine              the engine used to perform the actual compaction work.
     * @param storageDir          the base directory for storage operations.
     * @param fileCountThreshold  the number of SSTables that triggers a compaction run.
     * @param sstableProvider     the provider for SSTable state and metadata.
     */
    public CompactionScheduler(CompactionEngine engine, Path storageDir, int fileCountThreshold, SstableProvider sstableProvider) {
        this.engine = engine;
        this.storageDir = storageDir;
        this.fileCountThreshold = fileCountThreshold;
        this.sstableProvider = sstableProvider;
        
        this.monitor = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "compaction-monitor");
            t.setDaemon(true);
            return t;
        });

        // Use ForkJoinPool as mandated by Architecture Pattern 5 for CPU-bound merge tasks.
        this.worker = new ForkJoinPool(1, ForkJoinPool.defaultForkJoinWorkerThreadFactory, null, true);
    }

    /**
     * Starts the compaction monitor, which periodically (every 5 seconds) 
     * checks if compaction is required.
     */
    public void start() {
        logger.info("Starting compaction scheduler: threshold=" + fileCountThreshold + " files");
        monitor.scheduleWithFixedDelay(this::checkCompactionNeeded, 1, 5, TimeUnit.SECONDS);
    }

    /**
     * Core monitoring logic that evaluates the current system state against thresholds.
     * <p>
     * Logic Flow:
     * <ol>
     *     <li>Fetches compaction candidates from the provider.</li>
     *     <li>If candidate count exceeds threshold:
     *         <ul>
     *             <li>Tracks the duration since compaction was first needed (lag).</li>
     *             <li>Signals lag to the backpressure component.</li>
     *             <li>Attempts to schedule a {@link CompactionTask} if one isn't already running.</li>
     *         </ul>
     *     </li>
     *     <li>If candidate count is below threshold, resets lag and clears backpressure signal.</li>
     * </ol>
     */
    private void checkCompactionNeeded() {
        try {
            List<SstableReader> candidates = sstableProvider.getSstablesForCompaction();
            BackpressureSignal signal = sstableProvider.getBackpressureSignal();

            if (candidates != null && candidates.size() >= fileCountThreshold) {
                if (pendingSince == 0) {
                    pendingSince = System.currentTimeMillis();
                }

                if (signal != null) {
                    signal.signalCompactionLag(System.currentTimeMillis() - pendingSince);
                }

                if (!compactionInProgress.get() && compactionInProgress.compareAndSet(false, true)) {
                    // Decide if this is a major compaction (merging everything)
                    boolean isMajor = (candidates.size() >= sstableProvider.getTotalSstableCount());

                    CompactionTask.Callback callback = new CompactionTask.Callback() {
                        @Override
                        public void onComplete(Path result, List<SstableReader> inputs) {
                            try {
                                sstableProvider.onCompactionFinished(result, inputs);
                                // Re-check if we still need compaction after this run
                                List<SstableReader> remaining = sstableProvider.getSstablesForCompaction();
                                if (remaining == null || remaining.size() < fileCountThreshold) {
                                    pendingSince = 0;
                                    if (signal != null) signal.signalCompactionComplete();
                                }
                            } finally {
                                compactionInProgress.set(false);
                            }
                        }

                        @Override
                        public void onError(Throwable t) {
                            try {
                                logger.severe("Compaction worker error: " + t.getMessage());
                            } finally {
                                compactionInProgress.set(false);
                            }
                        }
                    };

                    CompactionTask task = new CompactionTask(engine, candidates, storageDir, isMajor, callback);
                    worker.execute(task);
                }
            } else {
                // Not enough candidates; reset lag tracking
                if (pendingSince != 0) {
                    pendingSince = 0;
                    if (signal != null) signal.signalCompactionComplete();
                }
            }
        } catch (Throwable t) {
            logger.severe("Error in compaction monitor: " + t.getMessage());
            // Ensure flag is reset if something failed before task submission to avoid deadlock
            compactionInProgress.set(false);
        }
    }

    /**
     * Shuts down the monitor and worker threads, waiting for pending tasks to complete.
     * <p>
     * This method follows a two-phase shutdown: first attempting a graceful stop, 
     * then forcing shutdown if tasks don't complete within the timeouts.
     */
    @Override
    public void close() {
        monitor.shutdown();
        worker.shutdown();
        try {
            if (!monitor.awaitTermination(5, TimeUnit.SECONDS)) monitor.shutdownNow();
            if (!worker.awaitTermination(30, TimeUnit.SECONDS)) worker.shutdownNow();
        } catch (InterruptedException e) {
            monitor.shutdownNow();
            worker.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }
}
