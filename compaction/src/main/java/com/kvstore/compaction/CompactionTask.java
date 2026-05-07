package com.kvstore.compaction;

import com.kvstore.sstable.SstableReader;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;
import java.util.logging.Logger;

/**
 * Encapsulates a single compaction operation to be executed in a background thread.
 * <p>
 * This task coordinates with the {@link CompactionEngine} to merge input SSTables
 * and notifies a {@link Callback} upon completion or failure. Compaction tasks 
 * are the fundamental unit of background maintenance in the LSM-tree, preventing
 * the number of files from growing boundlessly and reclaiming space from 
 * shadowed or deleted records.
 * <p>
 * This class implements {@link Runnable}, making it suitable for submission to 
 * thread pools like {@link java.util.concurrent.ForkJoinPool}.
 */
public class CompactionTask implements Runnable {
    /** Logger for tracking compaction progress and errors. */
    private static final Logger logger = Logger.getLogger(CompactionTask.class.getName());

    /** The engine that performs the heavy lifting of merging records. */
    private final CompactionEngine engine;
    /** The set of SSTables to be merged into a single output. */
    private final List<SstableReader> inputs;
    /** The directory where the new SSTable will be written. */
    private final Path outputDir;
    /** Whether this is a major compaction (merging all files and removing tombstones). */
    private final boolean isMajor;
    /** The callback to notify when the task finishes or fails. */
    private final Callback callback;

    /**
     * Callback interface for receiving notifications about the outcome of a compaction task.
     * <p>
     * Implementation should be thread-safe as it will be called from the background worker thread.
     */
    public interface Callback {
        /**
         * Invoked when the compaction task completes successfully.
         *
         * @param result the path to the newly created, compacted SSTable file.
         * @param inputs the list of original input SSTables that were successfully merged.
         */
        void onComplete(Path result, List<SstableReader> inputs);

        /**
         * Invoked when the compaction task fails due to an error.
         *
         * @param t the exception that caused the failure (e.g., I/O error).
         */
        void onError(Throwable t);
    }

    /**
     * Constructs a {@code CompactionTask}.
     *
     * @param engine    the compaction engine.
     * @param inputs    the list of input SSTables to be merged.
     * @param outputDir the directory where the output SSTable will be written.
     * @param isMajor   whether this is a major compaction.
     * @param callback  the callback to notify of the result.
     */
    public CompactionTask(CompactionEngine engine, List<SstableReader> inputs, Path outputDir, boolean isMajor, Callback callback) {
        this.engine = engine;
        this.inputs = inputs;
        this.outputDir = outputDir;
        this.isMajor = isMajor;
        this.callback = callback;
    }

    /**
     * Executes the compaction operation.
     * <p>
     * Logic:
     * <ol>
     *     <li>Generates a unique filename for the new SSTable.</li>
     *     <li>Calls {@link CompactionEngine#compact(List, Path, String, boolean)} to perform the merge.</li>
     *     <li>On success, invokes {@link Callback#onComplete(Path, List)}.</li>
     *     <li>On failure, logs the error and invokes {@link Callback#onError(Throwable)}.</li>
     * </ol>
     */
    @Override
    public void run() {
        if (inputs.isEmpty()) {
            return;
        }

        String newFileName = "compacted-" + UUID.randomUUID() + ".sst";
        try {
            logger.info("Starting compaction: merging " + inputs.size() + " files into " + newFileName);
            Path result = engine.compact(inputs, outputDir, newFileName, isMajor);
            logger.info("Compaction successful: " + result);
            if (callback != null) {
                callback.onComplete(result, inputs);
            }
        } catch (Throwable t) {
            logger.severe("Compaction failed for " + newFileName + ": " + t.getMessage());
            if (callback != null) {
                callback.onError(t);
            }
        }
    }
}
