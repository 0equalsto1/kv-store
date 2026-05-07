package com.kvstore.sstable;

import java.util.Optional;

/**
 * Represents the result of a read operation from an SSTable.
 * <p>
 * This record encapsulates both the status of the read (found, deleted, or not found)
 * and the actual data if the key was found. Using a dedicated result object avoids
 * null-checks and provides explicit semantic meaning to the outcome of a search.
 *
 * @param status The status of the read operation.
 * @param value The value associated with the key, or {@code null} if not found or deleted.
 */
public record ReadResult(ReadStatus status, byte[] value) {
    /**
     * Creates a {@code ReadResult} indicating that the key was found.
     *
     * @param value The non-null byte array representing the found value.
     * @return A {@code ReadResult} with {@link ReadStatus#FOUND}.
     */
    public static ReadResult found(byte[] value) {
        return new ReadResult(ReadStatus.FOUND, value);
    }
    
    /**
     * Creates a {@code ReadResult} indicating that the key was marked as deleted (tombstone).
     *
     * @return A {@code ReadResult} with {@link ReadStatus#DELETED} and a {@code null} value.
     */
    public static ReadResult deleted() {
        return new ReadResult(ReadStatus.DELETED, null);
    }
    
    /**
     * Creates a {@code ReadResult} indicating that the key was not found in the SSTable.
     *
     * @return A {@code ReadResult} with {@link ReadStatus#NOT_FOUND} and a {@code null} value.
     */
    public static ReadResult notFound() {
        return new ReadResult(ReadStatus.NOT_FOUND, null);
    }
}
