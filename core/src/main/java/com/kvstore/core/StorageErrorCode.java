package com.kvstore.core;

/**
 * Enumeration of error codes for all storage-related exceptions in the system.
 * <p>
 * These codes provide a structured way to identify and handle different types of
 * failures within the storage engine. They are categorized into logical ranges
 * to simplify monitoring and alerting:
 * <ul>
 *     <li><b>1000-1999: Data Corruption & Integrity:</b> Critical errors where data 
 *         on disk does not match its expected state (e.g., checksum failures).</li>
 *     <li><b>2000-2999: Resource Budget Violations:</b> Errors occurring when 
 *         configured limits (like memory) are exceeded.</li>
 *     <li><b>3000-3999: Durability & I/O Issues:</b> Failures during file system 
 *         operations or crash recovery.</li>
 *     <li><b>4000-4999: Concurrency & Synchronization Issues:</b> Problems related 
 *         to locks, timeouts, or system backpressure.</li>
 *     <li><b>5000-5999: API Misuse & Configuration Issues:</b> Errors resulting 
 *         from incorrect usage of the engine or invalid settings.</li>
 * </ul>
 */
public enum StorageErrorCode {
    /** 
     * Detected a CRC32 checksum mismatch while reading a record. 
     * This usually indicates bit rot or an incomplete write.
     */
    CHECKSUM_MISMATCH(1001, "CRC32 mismatch in record"),
    
    /** 
     * A record in the Write-Ahead Log (WAL) is unreadable, corrupted, or truncated.
     * This can occur during recovery if the system crashed during a write.
     */
    CORRUPT_WAL_RECORD(1002, "WAL record unreadable or truncated"),
    
    /** 
     * The header of an SSTable file is invalid or corrupted.
     * This prevents the file from being opened or used.
     */
    CORRUPT_SSTABLE_HEADER(1003, "SSTable header invalid or corrupted"),

    /** 
     * The global memory budget has been exceeded and no further allocations 
     * can be made until memory is released.
     */
    BUDGET_EXCEEDED(2001, "Memory budget limit exceeded"),
    
    /** 
     * Failed to evict enough memory from the cache to satisfy a new allocation request.
     * This can happen if all entries in the cache are pinned or if the cache is too small.
     */
    CACHE_EVICTION_FAILED(2002, "Could not evict enough memory for cache"),

    /** 
     * A generic, unclassified I/O failure occurred in the underlying file system.
     */
    IO_ERROR(3000, "Generic I/O failure"),
    
    /** 
     * Failed to write a record to the Write-Ahead Log (WAL). 
     * This is a critical error that may impact durability.
     */
    WAL_WRITE_FAILED(3001, "Failed to write to Write-Ahead Log"),
    
    /** 
     * Failed to sync the WAL to disk using {@code fsync}.
     * This means recent writes may not survive a power failure.
     */
    WAL_FLUSH_FAILED(3002, "Failed to fsync WAL to disk"),
    
    /** 
     * An error occurred during the crash recovery process, preventing the engine from starting.
     */
    RECOVERY_FAILED(3003, "Crash recovery failed"),

    /** 
     * Write operation was rejected or delayed because background compaction 
     * is falling too far behind (compaction lag).
     */
    WRITE_BACKPRESSURE(4001, "Write rejected due to compaction lag"),
    
    /** 
     * Timed out while waiting to acquire a concurrency lock.
     */
    LOCK_TIMEOUT(4002, "Lock acquisition timeout"),

    /** 
     * An operation was attempted on a storage engine instance that has already been closed.
     */
    ENGINE_CLOSED(5001, "Engine already closed"),
    
    /** 
     * The provided {@link EngineConfig} contains invalid or contradictory parameters.
     */
    INVALID_CONFIGURATION(5002, "Invalid engine configuration"),
    
    /** 
     * The requested key was not found in the database.
     */
    KEY_NOT_FOUND(5003, "Key does not exist in database");

    /** 
     * The numeric error code for programmatic classification and logging. 
     */
    public final int code;
    
    /** 
     * A human-readable description of the error, used as a prefix for exception messages. 
     */
    public final String description;

    /**
     * Constructs a {@code StorageErrorCode}.
     *
     * @param code        the unique numeric identifier for the error.
     * @param description a brief, user-friendly description of the error.
     */
    StorageErrorCode(int code, String description) {
        this.code = code;
        this.description = description;
    }
}
