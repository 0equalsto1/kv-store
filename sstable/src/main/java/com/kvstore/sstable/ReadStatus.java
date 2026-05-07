package com.kvstore.sstable;

/**
 * Enumeration of possible outcomes for a search operation in an SSTable.
 */
public enum ReadStatus {
    /**
     * The key was found and a value is present.
     */
    FOUND,
    
    /**
     * The key was found but is marked as deleted (tombstone).
     */
    DELETED,
    
    /**
     * The key was not found in the SSTable.
     */
    NOT_FOUND
}
