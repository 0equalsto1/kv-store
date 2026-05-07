package com.kvstore.core;

/**
 * Custom runtime exception thrown by the storage engine to signal operational failures.
 * <p>
 * This exception is used throughout the system to wrap lower-level errors (like {@link java.io.IOException})
 * or to signal domain-specific failures (like memory budget violations or checksum mismatches).
 * By encapsulating a {@link StorageErrorCode}, it allows catching code to programmatically 
 * determine the nature of the failure without parsing error messages.
 */
public class StorageException extends RuntimeException {
    /**
     * The specific error code classifying this failure.
     * This field is immutable and provides a stable identifier for the error type.
     */
    private final StorageErrorCode errorCode;

    /**
     * Constructs a new {@code StorageException} with the specified error code and detail message.
     * <p>
     * The detail message is automatically prefixed with the description from the error code
     * to ensure consistent log reporting.
     *
     * @param errorCode the high-level classification of the error, providing category and description.
     * @param details   additional context or specific details about why the failure occurred.
     */
    public StorageException(StorageErrorCode errorCode, String details) {
        super(errorCode.description + ": " + details);
        this.errorCode = errorCode;
    }

    /**
     * Constructs a new {@code StorageException} with the specified error code, detail message, and cause.
     * <p>
     * This constructor is preferred when wrapping other exceptions (e.g., I/O or concurrency errors)
     * to preserve the stack trace of the original failure.
     *
     * @param errorCode the high-level classification of the error.
     * @param details   additional context or specific details about the failure.
     * @param cause     the underlying cause of the exception (e.g., an {@code IOException}).
     */
    public StorageException(StorageErrorCode errorCode, String details, Throwable cause) {
        super(errorCode.description + ": " + details, cause);
        this.errorCode = errorCode;
    }

    /**
     * Returns the error code associated with this exception.
     *
     * @return the {@link StorageErrorCode} representing the category of the failure.
     */
    public StorageErrorCode getErrorCode() {
        return errorCode;
    }
}
