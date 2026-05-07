package com.kvstore.engine;

import com.kvstore.cache.BlockCache;
import com.kvstore.cache.LruBlockCache;
import com.kvstore.compaction.CompactionEngine;
import com.kvstore.compaction.CompactionScheduler;
import com.kvstore.concurrency.WriteBackpressure;
import com.kvstore.core.EngineConfig;
import com.kvstore.core.MemoryBudget;
import com.kvstore.core.StorageErrorCode;
import com.kvstore.core.StorageException;
import com.kvstore.indexing.BloomFilter;
import com.kvstore.memtable.MemTable;
import com.kvstore.memtable.MemTableImpl;
import com.kvstore.sstable.ReadResult;
import com.kvstore.sstable.ReadStatus;
import com.kvstore.sstable.SstableReader;
import com.kvstore.sstable.SstableWriter;
import com.kvstore.wal.WalWriter;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicReference;
import java.util.logging.Logger;

/**
 * The main orchestrator of the Log-Structured Merge-tree (LSM-tree) KV store engine.
 * 
 * <p>This class coordinates the lifecycle of major components including the {@link MemTable},
 * {@link WalWriter}, {@link SstableReader}, {@link BloomFilter}, and {@link CompactionEngine}.
 * It manages the transition of data from volatile memory to durable on-disk storage.</p>
 *
 * <p><b>Write Path:</b>
 * <ol>
 *   <li>Incoming writes ({@link #put(byte[], byte[])}) and deletes ({@link #delete(byte[])}) are 
 *       first appended to the Write-Ahead Log (WAL) to ensure durability.</li>
 *   <li>The operation is then applied to the in-memory {@link MemTable}.</li>
 *   <li>When the {@link MemTable} exceeds {@code EngineConfig.memtableMaxSizeBytes()}, 
 *       it is flushed to disk as a new SSTable (Sorted String Table).</li>
 * </ol></p>
 *
 * <p><b>Read Path:</b>
 * <ol>
 *   <li>The search begins in the active {@link MemTable}.</li>
 *   <li>If not found, the engine queries the {@link SstableReader}s in reverse 
 *       chronological order (newest to oldest).</li>
 *   <li>Each {@link SstableReader} uses a {@link BloomFilter} to quickly skip
 *       files that definitely do not contain the key.</li>
 * </ol></p>
 *
 * <p><b>Background Operations:</b>
 * The {@link CompactionScheduler} periodically merges multiple SSTables to reclaim 
 * space occupied by deleted or shadowed keys and to improve read performance by 
 * reducing the number of files to search.</p>
 */
public class StorageEngine implements AutoCloseable {
    /**
     * Logger for engine-level events.
     */
    private static final Logger logger = Logger.getLogger(StorageEngine.class.getName());

    /**
     * Component name for memory budget tracking.
     */
    private static final String COMPONENT_NAME = "engine";

    /**
     * Configuration parameters for the engine.
     */
    private final EngineConfig config;

    /**
     * Root directory for all engine data.
     */
    private final Path baseDir;

    /**
     * Global memory budget for all off-heap and tracked heap allocations.
     */
    private final MemoryBudget budget;

    /**
     * Block-level cache for SSTable data blocks.
     */
    private final BlockCache blockCache;

    /**
     * Coordinator for applying write backpressure when background tasks lag.
     */
    private final WriteBackpressure backpressure;
    
    /**
     * Current operational state of the engine.
     */
    private final AtomicReference<EngineState> state = new AtomicReference<>(EngineState.INITIALIZING);

    /**
     * Thread-safe list of active SSTable readers, ordered newest to oldest.
     */
    private final List<SstableReader> sstables = new CopyOnWriteArrayList<>();

    /**
     * Counter used to generate unique and monotonically increasing file names.
     */
    private final java.util.concurrent.atomic.AtomicLong fileCounter = new java.util.concurrent.atomic.AtomicLong(System.nanoTime());
    
    /**
     * The active in-memory table receiving writes.
     */
    private MemTable memTable;

    /**
     * The writer for the current Write-Ahead Log.
     */
    private WalWriter walWriter;

    /**
     * Scheduler for background compaction tasks.
     */
    private CompactionScheduler compactionScheduler;

    /**
     * Sub-directory where WAL files are stored.
     */
    private final Path walDir;

    /**
     * Sub-directory where SSTable files are stored.
     */
    private final Path dataDir;

    /**
     * Represents the operational states of the {@link StorageEngine}.
     */
    public enum EngineState {
        /** 
         * Engine is being configured and components are being instantiated. 
         * Transition to RUNNING or RECOVERING is expected.
         */
        INITIALIZING,

        /** 
         * Engine is replaying WAL files to restore in-memory state after a restart. 
         * Read/write operations are generally blocked during this phase.
         */
        RECOVERING,

        /** 
         * Engine is healthy and accepting read/write operations. 
         */
        RUNNING,

        /** 
         * Engine encountered a critical error (e.g., disk full, corruption) 
         * and is non-functional.
         */
        FAILED,

        /** 
         * Engine has been gracefully shut down and resources have been released. 
         */
        CLOSED
    }

    /**
     * Constructs a new {@code StorageEngine} instance.
     * 
     * <p>Initializes core shared resources like the {@link MemoryBudget}, 
     * {@link BlockCache}, and {@link WriteBackpressure}. The engine must still 
     * be started via {@link #start()} before use.</p>
     *
     * @param config  the engine configuration (thresholds, cache sizes, etc.)
     * @param baseDir the root directory where data and logs will be stored
     */
    public StorageEngine(EngineConfig config, Path baseDir) {
        this.config = config;
        this.baseDir = baseDir;
        this.walDir = baseDir.resolve("wal");
        this.dataDir = baseDir.resolve("data");
        
        this.budget = new MemoryBudget(config.maxMemoryBytes());
        this.budget.registerComponent(COMPONENT_NAME);
        this.blockCache = new LruBlockCache(config.blockCacheMaxSizeBytes(), budget);
        this.backpressure = new WriteBackpressure(config.backpressureLagThresholdMillis());
    }

    /**
     * Initializes the engine, performs recovery, and starts background services.
     * 
     * <p>The startup sequence is:
     * <ol>
     *   <li>Ensure data directories exist.</li>
     *   <li>Load all existing SSTable files from the data directory.</li>
     *   <li>Initialize the {@link MemTable}.</li>
     *   <li>Replay WAL files via {@link EngineRecovery} to restore non-flushed data.</li>
     *   <li>Initialize a new WAL for incoming writes.</li>
     *   <li>Start the compaction scheduler thread pool.</li>
     * </ol></p>
     *
     * @throws IOException      if directory creation or file loading fails
     * @throws StorageException if recovery fails or initial memory allocation is denied
     */
    public void start() throws IOException {
        if (!state.compareAndSet(EngineState.INITIALIZING, EngineState.RECOVERING)) {
            return;
        }

        try {
            Files.createDirectories(walDir);
            Files.createDirectories(dataDir);

            // 1. Load existing SSTables
            loadSstables();

            // 2. Initialize MemTable
            this.memTable = new MemTableImpl(budget);

            // 3. Recovery from WAL
            EngineRecovery recovery = new EngineRecovery(budget, memTable, this::flushDuringRecovery, config.memtableMaxSizeBytes());
            recovery.recover(walDir);
            
            // Clean up replayed WAL files after successful recovery
            cleanupOldWals();

            // 4. Initialize WAL Writer for new operations
            String walFileName = generateFileName("wal", ".wal");
            this.walWriter = new WalWriter(walDir.resolve(walFileName), 64 * 1024 * 1024L, budget);

            // 5. Initialize Compaction
            CompactionEngine compactionEngine = new CompactionEngine(budget);
            this.compactionScheduler = new CompactionScheduler(compactionEngine, dataDir, 4, new SstableProviderImpl());
            this.compactionScheduler.start();

            state.set(EngineState.RUNNING);
            logger.info("Storage Engine is RUNNING");
        } catch (Throwable t) {
            state.set(EngineState.FAILED);
            logger.severe("Storage Engine failed to start: " + t.getMessage());
            throw t;
        }
    }

    /**
     * Generates a unique file name using the provided prefix and suffix.
     *
     * @param prefix file name prefix (e.g., "wal" or "data")
     * @param suffix file extension (e.g., ".wal" or ".sst")
     * @return a unique string filename
     */
    private String generateFileName(String prefix, String suffix) {
        return prefix + "-" + fileCounter.getAndIncrement() + suffix;
    }

    /**
     * Callback for {@link EngineRecovery} to flush the MemTable during replay.
     *
     * @param mt the MemTable to flush
     */
    private void flushDuringRecovery(MemTable mt) {
        logger.info("Flushing MemTable during recovery...");
        performFlush(mt);
    }

    /**
     * Flushes the provided MemTable to a new SSTable file on disk.
     * 
     * <p>This method is synchronized to ensure that only one flush operation 
     * happens at a time, protecting the integrity of the {@code sstables} list.</p>
     *
     * @param mt the {@link MemTable} instance to flush
     * @throws StorageException if the flush operation fails due to I/O errors
     */
    private synchronized void performFlush(MemTable mt) {
        String fileName = generateFileName("data", ".sst");
        Path outputPath = dataDir.resolve(fileName);
        
        try (SstableWriter writer = new SstableWriter(budget)) {
            writer.flush(mt, outputPath);
            // ATOMICITY FIX: Initialize reader BEFORE clearing memtable to avoid a 
            // temporary gap in data visibility during the flush transition.
            SstableReader reader = new SstableReader(outputPath, budget, blockCache);
            sstables.add(0, reader); // Add as newest
            mt.clear();
        } catch (Exception e) {
            throw new StorageException(StorageErrorCode.IO_ERROR, "Flush failed", e);
        }
    }

    /**
     * Scans the data directory and initializes {@link SstableReader}s for all 
     * existing SSTable files. Files are loaded in order from newest to oldest.
     *
     * @throws IOException if a file listing or reading error occurs
     */
    private void loadSstables() throws IOException {
        try (var stream = Files.list(dataDir)) {
            List<Path> files = stream
                .filter(p -> p.getFileName().toString().endsWith(".sst"))
                .sorted(Comparator.comparingLong(this::extractFileNumber).reversed())
                .toList();
            
            for (Path file : files) {
                sstables.add(new SstableReader(file, budget, blockCache));
            }
        }
    }

    /**
     * Extracts the numeric sequence number from a file name.
     *
     * @param path the path to the file
     * @return the extracted long number, or 0 if none found
     */
    private long extractFileNumber(Path path) {
        String name = path.getFileName().toString();
        try {
            String numericPart = name.replaceAll("\\D+", "");
            return numericPart.isEmpty() ? 0 : Long.parseLong(numericPart);
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    /**
     * Inserts or updates a key-value pair in the store.
     * 
     * <p>The operation is first written to the WAL for durability. Once the WAL 
     * is successfully updated, the data is added to the in-memory {@link MemTable}.</p>
     *
     * @param key   the byte array key; must not be null
     * @param value the byte array value; must not be null
     * @throws InterruptedException if the thread is interrupted while waiting 
     *                              for backpressure capacity
     * @throws StorageException     if the engine is not running or a persistence error occurs
     */
    public void put(byte[] key, byte[] value) throws InterruptedException {
        ensureRunning();
        backpressure.waitForCapacity();
        
        walWriter.appendPut(key, value);
        memTable.put(key, value);
        
        checkFlush();
    }

    /**
     * Retrieves the value associated with the given key.
     * 
     * <p>The search priority is:
     * <ol>
     *   <li>Active {@link MemTable}</li>
     *   <li>SSTables (from newest to oldest)</li>
     * </ol></p>
     *
     * @param key the byte array key to search for
     * @return an {@link Optional} containing the value if found and not deleted, 
     *         otherwise an empty Optional.
     * @throws StorageException if the engine is not running or an IO error occurs during search
     */
    public Optional<byte[]> get(byte[] key) {
        ensureRunning();
        
        // 1. Check MemTable
        Optional<byte[]> result = memTable.get(key);
        if (result.isPresent()) {
            return result.get().length == 0 ? Optional.empty() : result;
        }
        
        // 2. Check SSTables
        for (SstableReader reader : sstables) {
            ReadResult readResult = reader.get(key);
            if (readResult.status() == ReadStatus.FOUND) {
                return Optional.of(readResult.value());
            } else if (readResult.status() == ReadStatus.DELETED) {
                // Tombstone encountered; the key is deleted
                return Optional.empty();
            }
        }
        
        return Optional.empty();
    }

    /**
     * Deletes the value associated with the given key.
     * 
     * <p>In an LSM-tree, this is implemented by writing a "tombstone" record 
     * to the WAL and MemTable. The key is effectively masked until a 
     * compaction process eventually removes the data physically.</p>
     *
     * @param key the key to delete
     * @throws InterruptedException if the thread is interrupted while waiting for backpressure
     * @throws StorageException     if the engine is not running or a persistence error occurs
     */
    public void delete(byte[] key) throws InterruptedException {
        ensureRunning();
        backpressure.waitForCapacity();
        
        walWriter.appendDelete(key);
        memTable.delete(key);
        
        checkFlush();
    }

    /**
     * Checks if the MemTable has reached its size limit and triggers a flush if so.
     * Also rotates the WAL and cleans up old WAL files upon completion.
     */
    private synchronized void checkFlush() {
        if (memTable.sizeInBytes() >= config.memtableMaxSizeBytes()) {
            logger.info("Flushing MemTable to SSTable...");
            performFlush(memTable);
            
            // Rotate WAL
            walWriter.close();
            String walFileName = generateFileName("wal", ".wal");
            try {
                this.walWriter = new WalWriter(walDir.resolve(walFileName), 64 * 1024 * 1024L, budget);
            } catch (Exception e) {
                throw new StorageException(StorageErrorCode.IO_ERROR, "Failed to create new WAL after flush", e);
            }
            
            // Clean up old WAL files (that are now safely persisted in SSTables)
            cleanupOldWals();
        }
    }

    /**
     * Deletes all WAL files that are not the current active WAL.
     */
    private void cleanupOldWals() {
        try (var stream = Files.list(walDir)) {
            stream.filter(p -> p.getFileName().toString().endsWith(".wal"))
                  .filter(p -> walWriter == null || !p.equals(walWriter.getPath()))
                  .forEach(p -> {
                      try { Files.deleteIfExists(p); } catch (IOException ignored) {}
                  });
        } catch (IOException ignored) {}
    }

    /**
     * Validates that the engine is in the {@link EngineState#RUNNING} state.
     *
     * @throws StorageException if the engine is not running
     */
    private void ensureRunning() {
        if (state.get() != EngineState.RUNNING) {
            throw new StorageException(StorageErrorCode.ENGINE_CLOSED, "Engine state: " + state.get());
        }
    }

    /**
     * Gracefully shuts down the storage engine.
     * 
     * <p>This method ensures that:
     * <ul>
     *   <li>The compaction scheduler is stopped.</li>
     *   <li>All active writers (WAL) are closed and forced to disk.</li>
     *   <li>All readers (SSTables) are closed and files are unmapped.</li>
     *   <li>All caches and memory budgets are released.</li>
     * </ul></p>
     *
     * @throws Exception if an error occurs during component shutdown
     */
    @Override
    public void close() throws Exception {
        if (state.getAndSet(EngineState.CLOSED) == EngineState.CLOSED) {
            return;
        }
        
        closeComponent("compactionScheduler", compactionScheduler);
        closeComponent("walWriter", walWriter);
        closeComponent("memTable", memTable);
        for (SstableReader reader : sstables) {
            closeComponent("sstableReader", reader);
        }
        closeComponent("blockCache", blockCache);
    }

    /**
     * Safely closes an {@link AutoCloseable} component and logs any failures.
     *
     * @param name      the name of the component for logging
     * @param component the component to close
     */
    private void closeComponent(String name, AutoCloseable component) {
        if (component == null) return;
        try {
            component.close();
        } catch (Exception e) {
            logger.warning("Failed to close " + name + ": " + e.getMessage());
        }
    }

    /**
     * Internal implementation of the SSTable provider for the compaction scheduler.
     * Handles the atomic replacement of SSTables after a successful compaction.
     */
    private class SstableProviderImpl implements CompactionScheduler.SstableProvider {
        /**
         * Returns a snapshot of the current active SSTable readers.
         *
         * @return a list of {@link SstableReader}s
         */
        @Override
        public List<SstableReader> getSstablesForCompaction() {
            return new ArrayList<>(sstables);
        }

        /**
         * Returns the current total count of SSTable files.
         *
         * @return the number of SSTables
         */
        @Override
        public int getTotalSstableCount() {
            return sstables.size();
        }

        /**
         * Callback invoked when a compaction cycle completes.
         * 
         * <p>This method replaces the old SSTables with the newly merged one in 
         * the engine's reader list and closes the old readers.</p>
         *
         * @param newSstable   the path to the newly created SSTable
         * @param oldSstables the list of SSTables that were merged
         */
        @Override
        public void onCompactionFinished(Path newSstable, List<SstableReader> oldSstables) {
            // Add the new compacted SSTable to the list
            sstables.add(0, new SstableReader(newSstable, budget, blockCache));
            // Remove the SSTables that were successfully merged
            sstables.removeAll(oldSstables);
            
            // Release resources for old readers
            for (SstableReader reader : oldSstables) {
                reader.close();
            }
        }

        /**
         * Returns the backpressure signal for the compaction scheduler.
         *
         * @return the {@link WriteBackpressure} signal
         */
        @Override
        public com.kvstore.concurrency.BackpressureSignal getBackpressureSignal() {
            return backpressure;
        }
    }
}
