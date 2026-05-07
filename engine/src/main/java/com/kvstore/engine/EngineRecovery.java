package com.kvstore.engine;

import com.kvstore.core.MemoryBudget;
import com.kvstore.core.StorageErrorCode;
import com.kvstore.core.StorageException;
import com.kvstore.memtable.MemTable;
import com.kvstore.wal.WalReader;
import com.kvstore.wal.WalRecord;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.logging.Logger;
import java.util.stream.Stream;

/**
 * Orchestrates the recovery of the engine state from Write-Ahead Log (WAL) files.
 * 
 * <p>Recovery is a critical startup phase in an LSM-tree engine. Since the 
 * {@link MemTable} is volatile, any data not yet flushed to an SSTable would 
 * be lost in a crash. The {@code EngineRecovery} process restores this data by:
 * <ol>
 *   <li>Identifying all available WAL files in the WAL directory.</li>
 *   <li>Sorting them chronologically by their sequence numbers.</li>
 *   <li>Replaying each record (PUT/DELETE) into a fresh {@link MemTable}.</li>
 * </ol></p>
 *
 * <p>If the {@link MemTable} becomes too large during replay, the recovery process 
 * triggers a flush via the {@link RecoveryFlushHandler} to prevent 
 * {@link OutOfMemoryError}s and to respect the configured memory limits.</p>
 */
public class EngineRecovery {
    /**
     * Logger for recovery-related events.
     */
    private static final Logger logger = Logger.getLogger(EngineRecovery.class.getName());

    /**
     * The global memory budget tracker.
     */
    private final MemoryBudget budget;

    /**
     * The target MemTable where WAL records are replayed.
     */
    private final MemTable memTable;

    /**
     * Callback for flushing the MemTable if it grows too large during recovery.
     */
    private final RecoveryFlushHandler flushHandler;

    /**
     * The memory threshold (in bytes) that triggers a flush during recovery.
     */
    private final long flushThreshold;

    /**
     * Functional interface for handling MemTable flushes during the recovery process.
     * 
     * <p>Implementations typically interact with the {@link StorageEngine} to 
     * write the current {@link MemTable} state to a new SSTable file.</p>
     */
    public interface RecoveryFlushHandler {
        /**
         * Invoked when the MemTable reaches its capacity during recovery.
         *
         * @param memTable the {@link MemTable} that needs to be flushed
         */
        void onFlushRequired(MemTable memTable);
    }

    /**
     * Constructs a new {@code EngineRecovery} instance.
     *
     * @param budget         the {@link MemoryBudget} for tracking allocations during recovery
     * @param memTable       the {@link MemTable} where WAL records will be replayed
     * @param flushHandler   the callback for handling flushes during recovery
     * @param flushThreshold the size in bytes at which a flush should be triggered
     */
    public EngineRecovery(MemoryBudget budget, MemTable memTable, RecoveryFlushHandler flushHandler, long flushThreshold) {
        this.budget = budget;
        this.memTable = memTable;
        this.flushHandler = flushHandler;
        this.flushThreshold = flushThreshold;
    }

    /**
     * Scans the WAL directory and replays all records into the MemTable in sequential order.
     * 
     * <p>This method performs the following steps:
     * <ol>
     *   <li>Lists all files with the ".wal" extension in {@code walDir}.</li>
     *   <li>Sorts files based on the sequence number in their filename.</li>
     *   <li>Iterates through each file using a {@link WalReader}.</li>
     *   <li>For each {@link WalRecord}, calls {@link #replayRecord(WalRecord)}.</li>
     *   <li>Triggers the {@link RecoveryFlushHandler} if {@code flushThreshold} is reached.</li>
     * </ol></p>
     *
     * @param walDir the directory containing WAL files
     * @throws StorageException if recovery fails due to I/O errors, budget exhaustion, 
     *                          or critical WAL corruption
     */
    public void recover(Path walDir) {
        if (!Files.exists(walDir)) {
            logger.info("WAL directory does not exist, skipping recovery: " + walDir);
            return;
        }

        logger.info("Starting engine recovery from: " + walDir);
        
        List<Path> walFiles;
        try (Stream<Path> stream = Files.list(walDir)) {
            walFiles = stream
                .filter(p -> p.getFileName().toString().endsWith(".wal"))
                .sorted(Comparator.comparing(this::extractFileNumber))
                .toList();
        } catch (IOException e) {
            throw new StorageException(StorageErrorCode.RECOVERY_FAILED, "Failed to list WAL files", e);
        }

        if (walFiles.isEmpty()) {
            logger.info("No WAL files found for recovery.");
            return;
        }

        long recordsReplayed = 0;
        for (Path walFile : walFiles) {
            logger.info("Replaying WAL file: " + walFile.getFileName());
            try (WalReader reader = new WalReader(walFile, budget)) {
                for (WalRecord record : reader) {
                    replayRecord(record);
                    recordsReplayed++;
                    
                    // Periodically check if we need to flush to keep recovery memory usage within limits
                    if (memTable.sizeInBytes() >= flushThreshold && flushHandler != null) {
                        logger.info("Flush threshold reached during recovery (" + memTable.sizeInBytes() + " >= " + flushThreshold + "), triggering flush...");
                        flushHandler.onFlushRequired(memTable);
                    }
                }
            } catch (StorageException e) {
                // Log corruption but consider re-throwing if the error is unrecoverable
                if (e.getErrorCode() == StorageErrorCode.CORRUPT_WAL_RECORD) {
                    logger.severe("Recovery encountered CORRUPT_WAL_RECORD in " + walFile.getFileName() + ": " + e.getMessage());
                } else {
                    logger.severe("Recovery failed with StorageException: " + e.getErrorCode() + " - " + e.getMessage());
                }
                
                // Re-throw critical resource or I/O errors
                if (e.getErrorCode() == StorageErrorCode.BUDGET_EXCEEDED || e.getErrorCode() == StorageErrorCode.IO_ERROR) {
                    throw e;
                }
                logger.warning("Recovery stopped for " + walFile.getFileName() + " due to storage error: " + e.getMessage());
            } catch (Exception e) {
                logger.severe("Recovery failed with unexpected exception: " + e.getClass().getName() + " - " + e.getMessage());
                throw new StorageException(StorageErrorCode.RECOVERY_FAILED, "Unexpected error during recovery", e);
            }
        }

        logger.info("Recovery complete. Replayed " + recordsReplayed + " records.");
    }

    /**
     * Extracts the numeric sequence number from a WAL filename.
     * Expects formats like "wal-123.wal".
     *
     * @param path the path to the WAL file
     * @return the sequence number as a long
     */
    private long extractFileNumber(Path path) {
        String name = path.getFileName().toString();
        try {
            // Extracts only digits from the filename
            String numericPart = name.replaceAll("\\D+", "");
            return numericPart.isEmpty() ? 0 : Long.parseLong(numericPart);
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    /**
     * Replays a single WAL record into the active MemTable.
     * 
     * <p>Supports {@link WalRecord#TYPE_PUT} for inserts/updates and 
     * {@link WalRecord#TYPE_DELETE} for tombstones.</p>
     *
     * @param record the {@link WalRecord} to replay
     */
    private void replayRecord(WalRecord record) {
        if (record.type() == WalRecord.TYPE_PUT) {
            memTable.put(record.key(), record.value());
        } else if (record.type() == WalRecord.TYPE_DELETE) {
            memTable.delete(record.key());
        } else {
            logger.warning("Skipping unknown WAL record type: " + record.type());
        }
    }
}
