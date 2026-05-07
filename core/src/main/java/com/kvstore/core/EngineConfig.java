package com.kvstore.core;

/**
 * Immutable configuration class for the KV-store engine.
 * <p>
 * This class serves as a container for all tuning parameters and resource limits
 * used by the storage engine. It covers various aspects including:
 * <ul>
 *     <li><b>Memory Management:</b> Total budget, MemTable size, and Cache size.</li>
 *     <li><b>Compaction:</b> Buffering and backpressure thresholds.</li>
 *     <li><b>Durability:</b> WAL fsync intervals and batching.</li>
 *     <li><b>Efficiency:</b> Bloom Filter precision.</li>
 * </ul>
 * Instances should be created using the static {@link Builder}.
 */
public class EngineConfig {
    /** Total maximum memory budget for the engine (heap + off-heap). */
    private final long maxMemoryBytes;
    /** Maximum size for an individual MemTable before it is flushed to disk as an SSTable. */
    private final long memtableMaxSizeBytes;
    /** Maximum size for the off-heap block cache. */
    private final long blockCacheMaxSizeBytes;
    /** Maximum buffer size used during compaction merge operations. */
    private final long compactionMaxBufferSize;
    /** Time threshold in milliseconds for compaction lag before applying write backpressure. */
    private final long backpressureLagThresholdMillis;
    /** Number of bits per key to use in SSTable Bloom Filters. */
    private final int bloomFilterBitsPerKey;
    /** Interval in milliseconds at which the WAL is forcefully synced to disk. */
    private final long walFsyncIntervalMillis;
    /** Maximum number of records to batch in the WAL before triggering a sync. */
    private final int walBatchSize;

    /**
     * Private constructor used by the {@link Builder}.
     *
     * @param builder the builder instance containing the configuration values.
     */
    private EngineConfig(Builder builder) {
        this.maxMemoryBytes = builder.maxMemoryBytes;
        this.memtableMaxSizeBytes = builder.memtableMaxSizeBytes;
        this.blockCacheMaxSizeBytes = builder.blockCacheMaxSizeBytes;
        this.compactionMaxBufferSize = builder.compactionMaxBufferSize;
        this.backpressureLagThresholdMillis = builder.backpressureLagThresholdMillis;
        this.bloomFilterBitsPerKey = builder.bloomFilterBitsPerKey;
        this.walFsyncIntervalMillis = builder.walFsyncIntervalMillis;
        this.walBatchSize = builder.walBatchSize;
    }

    /**
     * Builder for {@link EngineConfig} instances.
     * <p>
     * Provides sensible defaults for a high-performance, production-ready environment.
     * All parameters can be overridden before calling {@link #build()}.
     */
    public static class Builder {
        /** Default total memory: 100GB. Suitable for large server deployments. */
        private long maxMemoryBytes = 100 * 1024 * 1024 * 1024L; 
        /** Default MemTable size: 64MB. Balances write throughput and memory usage. */
        private long memtableMaxSizeBytes = 64 * 1024 * 1024L;   
        /** Default Cache size: 8GB. Allocates a substantial portion of memory for hot data. */
        private long blockCacheMaxSizeBytes = 8 * 1024 * 1024 * 1024L; 
        /** Default Compaction buffer: 64MB. Limits memory used by the background compaction engine. */
        private long compactionMaxBufferSize = 64 * 1024 * 1024L; 
        /** Default Backpressure threshold: 30 seconds. Prevents SSTable count from spiraling. */
        private long backpressureLagThresholdMillis = 30000;      
        /** Default Bloom Filter bits: 10. Provides ~1% false positive rate. */
        private int bloomFilterBitsPerKey = 10;
        /** Default WAL sync interval: 100ms. Provides a good trade-off between durability and latency. */
        private long walFsyncIntervalMillis = 100L;
        /** Default WAL batch size: 1000 records. */
        private int walBatchSize = 1000;

        /**
         * Sets the total maximum memory budget for the engine.
         *
         * @param maxMemoryBytes maximum memory in bytes.
         * @return this builder instance.
         */
        public Builder maxMemoryBytes(long maxMemoryBytes) {
            this.maxMemoryBytes = maxMemoryBytes;
            return this;
        }

        /**
         * Sets the maximum size for an individual MemTable before it is flushed.
         *
         * @param memtableMaxSizeBytes MemTable size in bytes.
         * @return this builder instance.
         */
        public Builder memtableMaxSizeBytes(long memtableMaxSizeBytes) {
            this.memtableMaxSizeBytes = memtableMaxSizeBytes;
            return this;
        }

        /**
         * Sets the maximum size for the off-heap block cache.
         *
         * @param blockCacheMaxSizeBytes cache size in bytes.
         * @return this builder instance.
         */
        public Builder blockCacheMaxSizeBytes(long blockCacheMaxSizeBytes) {
            this.blockCacheMaxSizeBytes = blockCacheMaxSizeBytes;
            return this;
        }

        /**
         * Sets the maximum buffer size used during compaction operations.
         *
         * @param compactionMaxBufferSize buffer size in bytes.
         * @return this builder instance.
         */
        public Builder compactionMaxBufferSize(long compactionMaxBufferSize) {
            this.compactionMaxBufferSize = compactionMaxBufferSize;
            return this;
        }

        /**
         * Sets the compaction lag threshold at which write backpressure will be applied.
         *
         * @param backpressureLagThresholdMillis threshold in milliseconds.
         * @return this builder instance.
         */
        public Builder backpressureLagThresholdMillis(long backpressureLagThresholdMillis) {
            this.backpressureLagThresholdMillis = backpressureLagThresholdMillis;
            return this;
        }

        /**
         * Sets the number of bits per key to use in Bloom Filters for SSTables.
         * <p>
         * Higher values reduce false positives (improving read speed for non-existent keys)
         * but increase the memory/disk footprint of the SSTable index.
         *
         * @param bloomFilterBitsPerKey bits per key.
         * @return this builder instance.
         */
        public Builder bloomFilterBitsPerKey(int bloomFilterBitsPerKey) {
            this.bloomFilterBitsPerKey = bloomFilterBitsPerKey;
            return this;
        }

        /**
         * Sets the interval at which the WAL is forcefully synced to disk.
         *
         * @param walFsyncIntervalMillis sync interval in milliseconds.
         * @return this builder instance.
         */
        public Builder walFsyncIntervalMillis(long walFsyncIntervalMillis) {
            this.walFsyncIntervalMillis = walFsyncIntervalMillis;
            return this;
        }

        /**
         * Sets the number of WAL records to batch before a sync operation.
         *
         * @param walBatchSize batch size.
         * @return this builder instance.
         */
        public Builder walBatchSize(int walBatchSize) {
            this.walBatchSize = walBatchSize;
            return this;
        }

        /**
         * Validates all configuration parameters and builds a new {@link EngineConfig} instance.
         * <p>
         * Validation checks:
         * <ul>
         *     <li>All numeric values must be positive.</li>
         *     <li>Total memory budget must be able to accommodate both MemTable and Cache.</li>
         * </ul>
         *
         * @return a validated EngineConfig instance.
         * @throws StorageException if any configuration parameter is invalid or inconsistent.
         */
        public EngineConfig build() {
            if (maxMemoryBytes <= 0) {
                throw new StorageException(StorageErrorCode.INVALID_CONFIGURATION,
                    "maxMemoryBytes must be positive: " + maxMemoryBytes);
            }
            if (memtableMaxSizeBytes <= 0) {
                throw new StorageException(StorageErrorCode.INVALID_CONFIGURATION,
                    "memtableMaxSizeBytes must be positive: " + memtableMaxSizeBytes);
            }
            if (blockCacheMaxSizeBytes <= 0) {
                throw new StorageException(StorageErrorCode.INVALID_CONFIGURATION,
                    "blockCacheMaxSizeBytes must be positive: " + blockCacheMaxSizeBytes);
            }
            if (bloomFilterBitsPerKey <= 0) {
                throw new StorageException(StorageErrorCode.INVALID_CONFIGURATION,
                    "bloomFilterBitsPerKey must be positive: " + bloomFilterBitsPerKey);
            }
            if (walFsyncIntervalMillis <= 0) {
                throw new StorageException(StorageErrorCode.INVALID_CONFIGURATION,
                    "walFsyncIntervalMillis must be positive: " + walFsyncIntervalMillis);
            }
            if (walBatchSize <= 0) {
                throw new StorageException(StorageErrorCode.INVALID_CONFIGURATION,
                    "walBatchSize must be positive: " + walBatchSize);
            }
            if (compactionMaxBufferSize <= 0) {
                throw new StorageException(StorageErrorCode.INVALID_CONFIGURATION,
                    "compactionMaxBufferSize must be positive: " + compactionMaxBufferSize);
            }
            if (backpressureLagThresholdMillis <= 0) {
                throw new StorageException(StorageErrorCode.INVALID_CONFIGURATION,
                    "backpressureLagThresholdMillis must be positive: " + backpressureLagThresholdMillis);
            }
            if (memtableMaxSizeBytes + blockCacheMaxSizeBytes > maxMemoryBytes) {
                throw new StorageException(StorageErrorCode.INVALID_CONFIGURATION,
                    "MemTable + Cache exceeds total memory budget");
            }
            return new EngineConfig(this);
        }
    }

    /** 
     * Returns the total maximum memory budget in bytes. 
     * @return maximum memory in bytes.
     */
    public long maxMemoryBytes() {
        return maxMemoryBytes;
    }

    /** 
     * Returns the maximum size for an individual MemTable in bytes. 
     * @return MemTable size in bytes.
     */
    public long memtableMaxSizeBytes() {
        return memtableMaxSizeBytes;
    }

    /** 
     * Returns the maximum size for the off-heap block cache in bytes. 
     * @return Cache size in bytes.
     */
    public long blockCacheMaxSizeBytes() {
        return blockCacheMaxSizeBytes;
    }

    /** 
     * Returns the maximum buffer size for compaction operations. 
     * @return buffer size in bytes.
     */
    public long compactionMaxBufferSize() {
        return compactionMaxBufferSize;
    }

    /** 
     * Returns the compaction lag threshold for write backpressure in milliseconds. 
     * @return threshold in milliseconds.
     */
    public long backpressureLagThresholdMillis() {
        return backpressureLagThresholdMillis;
    }

    /** 
     * Returns the number of bits per key used in Bloom Filters. 
     * @return bits per key.
     */
    public int bloomFilterBitsPerKey() {
        return bloomFilterBitsPerKey;
    }

    /** 
     * Returns the WAL fsync interval in milliseconds. 
     * @return fsync interval.
     */
    public long walFsyncIntervalMillis() {
        return walFsyncIntervalMillis;
    }

    /** 
     * Returns the WAL record batch size. 
     * @return batch size.
     */
    public int walBatchSize() {
        return walBatchSize;
    }
}
