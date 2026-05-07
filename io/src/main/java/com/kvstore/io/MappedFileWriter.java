package com.kvstore.io;

import com.kvstore.core.MemoryBudget;
import com.kvstore.core.StorageErrorCode;
import com.kvstore.core.StorageException;

import java.io.IOException;
import java.io.RandomAccessFile;
import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.nio.channels.FileChannel;
import java.nio.file.Path;

/**
 * A read-write wrapper for a memory-mapped file using Java's Foreign Function & Memory (FFM) API.
 * 
 * <p>This class provides efficient append-only and random-access write capabilities 
 * by mapping the file into the process's virtual address space. By using {@link MemorySegment}
 * and {@link Arena}, it avoids the overhead of traditional file I/O operations and 
 * provides a more modern alternative to {@code java.nio.MappedByteBuffer}.</p>
 *
 * <p><b>Lifecycle & Memory Management:</b>
 * The file size is pre-allocated upon creation. The memory used by the mapping is 
 * explicitly tracked within a {@link MemoryBudget}. When {@link #close()} is called, 
 * the {@link Arena} is closed, which unmaps the file and releases the associated 
 * memory budget.</p>
 *
 * <p><b>Concurrency:</b>
 * Methods that modify the internal {@code position} or the {@code segment} are 
 * {@code synchronized} to ensure thread safety when multiple threads are writing 
 * to the same file (e.g., during concurrent WAL logging or SSTable creation).</p>
 */
public class MappedFileWriter implements AutoCloseable {
    /**
     * Component name for budget tracking.
     */
    private final String componentName;

    /**
     * Path to the underlying file.
     */
    private final Path path;

    /**
     * Total size of the mapped file in bytes.
     */
    private final long size;

    /**
     * Global memory budget for resource tracking.
     */
    private final MemoryBudget budget;

    /**
     * The arena that controls the lifecycle of the mapped memory.
     */
    private final Arena arena;

    /**
     * The memory segment representing the mapped file.
     */
    private final MemorySegment segment;

    /**
     * The current write position for append-style operations.
     */
    private long position = 0;

    /**
     * Constructs a {@code MappedFileWriter} and maps a shared file for writing.
     * Uses the default component name "io".
     *
     * @param path   the {@link Path} to the file to map
     * @param size   the size of the mapping in bytes
     * @param budget the {@link MemoryBudget} for tracking allocation
     * @throws StorageException if the budget is exceeded or mapping fails
     */
    public MappedFileWriter(Path path, long size, MemoryBudget budget) {
        this(path, size, budget, "io", true);
    }

    /**
     * Constructs a {@code MappedFileWriter} and maps a shared file for writing with a specific component name.
     *
     * @param path          the {@link Path} to the file to map
     * @param size          the size of the mapping in bytes
     * @param budget        the {@link MemoryBudget} for tracking allocation
     * @param componentName the name of the component for budget tracking
     * @throws StorageException if the budget is exceeded or mapping fails
     */
    public MappedFileWriter(Path path, long size, MemoryBudget budget, String componentName) {
        this(path, size, budget, componentName, true);
    }

    /**
     * Constructs a {@code MappedFileWriter} and maps a file for writing.
     *
     * <p>This constructor pre-allocates the file to the requested size on disk 
     * using {@link RandomAccessFile#setLength(long)} before mapping it into memory. 
     * It also registers and allocates the requested size from the {@link MemoryBudget}.</p>
     *
     * @param path          the {@link Path} to the file to map
     * @param size          the size of the mapping in bytes
     * @param budget        the {@link MemoryBudget} for tracking allocation
     * @param componentName the name of the component for budget tracking
     * @param shared        true if the mapping should use a shared arena (thread-safe close), 
     *                      false for a confined one (single-threaded access)
     * @throws StorageException if the budget is exceeded, the file cannot be opened, 
     *                          or the mapping operation fails
     */
    public MappedFileWriter(Path path, long size, MemoryBudget budget, String componentName, boolean shared) {
        this.path = path;
        this.size = size;
        this.budget = budget;
        this.componentName = componentName;
        
        budget.registerComponent(componentName);
        budget.allocate(componentName, size);

        Arena tempArena = null;
        try {
            tempArena = shared ? Arena.ofShared() : Arena.ofConfined();
            try (RandomAccessFile raf = new RandomAccessFile(path.toFile(), "rw")) {
                raf.setLength(size);
                this.segment = raf.getChannel().map(FileChannel.MapMode.READ_WRITE, 0, size, tempArena);
            }
            this.arena = tempArena;
        } catch (IOException e) {
            budget.deallocate(componentName, size);
            if (tempArena != null) tempArena.close();
            throw new StorageException(StorageErrorCode.IO_ERROR, "Failed to map file for writing: " + path, e);
        } catch (Throwable t) {
            budget.deallocate(componentName, size);
            if (tempArena != null) tempArena.close();
            throw t;
        }
    }

    /**
     * Appends the given data to the file at the current position and advances the pointer.
     * 
     * <p>This method uses {@link MemorySegment#copy(MemorySegment, long, MemorySegment, long, long)} 
     * for high-performance memory copying from the input byte array to the mapped segment.</p>
     *
     * @param data the byte array containing data to write
     * @throws StorageException if the write operation would exceed the mapped size 
     *                          or if an arithmetic overflow occurs during position calculation
     */
    public synchronized void write(byte[] data) {
        try {
            long end = Math.addExact(position, data.length);
            if (end > size) {
                throw new StorageException(StorageErrorCode.IO_ERROR, "Write exceeds mapped size: " + end + " > " + size);  
            }
            MemorySegment.copy(MemorySegment.ofArray(data), 0, segment, position, data.length);
            position = end;
        } catch (ArithmeticException e) {
            throw new StorageException(StorageErrorCode.IO_ERROR, "Write offset overflow", e);
        }
    }

    /**
     * Executes a custom write action at the current position and advances the pointer.
     * 
     * <p>This method is useful for writing primitive types (like ints or longs) 
     * directly into the segment without creating temporary byte arrays.</p>
     *
     * @param length the number of bytes that the action is expected to write
     * @param action the {@link WriterAction} callback that performs the actual write
     * @throws StorageException if the write exceeds the mapped size or if the 
     *                          offset calculation overflows
     */
    public synchronized void writeAtCurrentPosition(long length, WriterAction action) {
        try {
            long end = Math.addExact(position, length);
            if (end > size) {
                throw new StorageException(StorageErrorCode.IO_ERROR, "Direct write exceeds mapped size");
            }
            action.write(segment, position);
            position = end;
        } catch (ArithmeticException e) {
            throw new StorageException(StorageErrorCode.IO_ERROR, "Write offset overflow", e);
        }
    }

    /**
     * Executes a custom write action at a specific absolute offset without 
     * affecting the current position.
     *
     * @param offset the starting absolute offset within the segment for the write
     * @param length the number of bytes that the action is expected to write
     * @param action the {@link WriterAction} callback that performs the actual write
     * @throws StorageException if the requested range is out of bounds or overflows
     */
    public synchronized void writeAt(long offset, long length, WriterAction action) {
        try {
            long end = Math.addExact(offset, length);
            if (offset < 0 || end > size) {
                throw new StorageException(StorageErrorCode.IO_ERROR, "Absolute write exceeds mapped size");
            }
            action.write(segment, offset);
        } catch (ArithmeticException e) {
            throw new StorageException(StorageErrorCode.IO_ERROR, "Absolute write offset overflow", e);
        }
    }

    /**
     * Functional interface for performing direct writes to a {@link MemorySegment}.
     * This allows for optimized writes of structured data or primitives.
     */
    @FunctionalInterface
    public interface WriterAction {
        /**
         * Invoked to perform the write operation at the specified offset.
         *
         * @param segment the {@link MemorySegment} representing the mapped file
         * @param offset  the absolute offset within the segment where writing should begin
         */
        void write(MemorySegment segment, long offset);
    }

    /**
     * Writes a single byte at the specified absolute offset.
     *
     * @param offset the absolute offset to write to
     * @param value  the byte value to write
     * @throws StorageException if the offset is out of bounds
     */
    public synchronized void put(long offset, byte value) {
        if (offset < 0 || offset >= size) {
            throw new StorageException(StorageErrorCode.IO_ERROR, "Offset " + offset + " is out of bounds for size " + size);
        }
        segment.set(ValueLayout.JAVA_BYTE, offset, value);
    }

    /**
     * Forces any pending changes made to the mapped segment to be written to 
     * the underlying storage device.
     * 
     * <p>This ensures that writes are durable in the event of a system crash, 
     * although it can be an expensive operation.</p>
     */
    public void force() {
        segment.force();
    }

    /**
     * Closes the writer, unmapping the file and releasing the associated memory budget.
     * 
     * <p>Closing the {@link Arena} triggers the unmapping of the {@link MemorySegment}.
     * Once closed, any further access to the segment will result in an {@link IllegalStateException}.</p>
     */
    @Override
    public synchronized void close() {
        try {
            arena.close();
        } finally {
            budget.deallocate(componentName, size);
        }
    }

    /**
     * Returns the current write position.
     *
     * @return the current position in bytes from the start of the file
     */
    public synchronized long getPosition() {
        return position;
    }

    /**
     * Returns the underlying {@link MemorySegment} for direct access.
     * 
     * <p><b>Warning:</b> Use with caution. Direct access to the segment skips 
     * the synchronization provided by this class.</p>
     *
     * @return the raw memory segment
     */
    public MemorySegment getSegment() {
        return segment;
    }
}
