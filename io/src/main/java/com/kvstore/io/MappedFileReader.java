package com.kvstore.io;

import com.kvstore.core.MemoryBudget;
import com.kvstore.core.StorageErrorCode;
import com.kvstore.core.StorageException;

import java.io.IOException;
import java.io.RandomAccessFile;
import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.nio.channels.FileChannel;
import java.nio.file.Path;

/**
 * A read-only wrapper for a memory-mapped file using Java's Foreign Function & Memory (FFM) API.
 * 
 * <p>This class provides efficient, low-latency access to file contents by mapping 
 * them directly into the process's virtual address space. It leverages {@link MemorySegment} 
 * for safe and efficient memory access and {@link Arena} for deterministic lifecycle management.</p>
 *
 * <p><b>Memory Management:</b>
 * The class integrates with the {@link MemoryBudget} to ensure that the cumulative 
 * size of all mapped files is tracked and does not exceed the system's configured limits. 
 * Upon closing, the file is unmapped and the budget is released.</p>
 *
 * <p><b>Thread Safety:</b>
 * This class is thread-safe for concurrent read operations as it does not maintain 
 * internal pointers (like {@code position}) that are modified during reads. Each 
 * {@link #read(long, byte[])} operation specifies an absolute offset.</p>
 */
public class MappedFileReader implements AutoCloseable {
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
     * Constructs a {@code MappedFileReader} and maps the file at the given path.
     * Uses the default "io" component name for budget tracking.
     *
     * @param path   the {@link Path} to the file to map
     * @param budget the {@link MemoryBudget} for tracking allocation
     * @throws StorageException if the file cannot be found, the budget is exceeded, or mapping fails
     */
    public MappedFileReader(Path path, MemoryBudget budget) {
        this(path, budget, "io");
    }

    /**
     * Constructs a {@code MappedFileReader} and maps the file at the given path.
     *
     * <p>This constructor determines the file size, allocates it from the {@link MemoryBudget}, 
     * and maps the file into memory using {@link FileChannel.MapMode#READ_ONLY}.</p>
     *
     * @param path          the {@link Path} to the file to map
     * @param budget        the {@link MemoryBudget} for tracking allocation
     * @param componentName the name of the component for budget tracking
     * @throws StorageException if the file size cannot be determined, the budget is exceeded, 
     *                          or the I/O operation fails
     */
    public MappedFileReader(Path path, MemoryBudget budget, String componentName) {
        this.path = path;
        this.budget = budget;
        this.componentName = componentName;

        budget.registerComponent(componentName);

        long fileSize;
        try {
            fileSize = path.toFile().length();
            if (fileSize < 0) {
                 throw new IOException("Could not determine file length: " + path);
            }
        } catch (Exception e) {
            throw new StorageException(StorageErrorCode.IO_ERROR, "Failed to check file length: " + path, e);
        }

        this.size = fileSize;
        budget.allocate(componentName, size);

        Arena tempArena = null;
        try {
            tempArena = Arena.ofShared();
            try (RandomAccessFile raf = new RandomAccessFile(path.toFile(), "r")) {
                this.segment = raf.getChannel().map(FileChannel.MapMode.READ_ONLY, 0, size, tempArena);
            }
            this.arena = tempArena;
        } catch (IOException e) {
            budget.deallocate(componentName, size);
            if (tempArena != null) tempArena.close();
            throw new StorageException(StorageErrorCode.IO_ERROR, "Failed to map file for reading: " + path, e);
        } catch (Throwable t) {
            budget.deallocate(componentName, size);
            if (tempArena != null) tempArena.close();
            throw t;
        }
    }

    /**
     * Internal no-args constructor used for creating proxy objects (like in {@link FileArenaPool}).
     * Does not perform any mapping or budget allocation.
     */
    protected MappedFileReader() {
        this.path = null;
        this.budget = null;
        this.componentName = null;
        this.size = 0;
        this.arena = null;
        this.segment = null;
    }

    /**
     * Reads a range of bytes from the mapped file into the destination array.
     * 
     * <p>Uses {@link MemorySegment#copy(MemorySegment, long, MemorySegment, long, long)} 
     * to perform a high-performance memory-to-memory copy from the mapped segment 
     * into the heap-allocated destination array.</p>
     *
     * @param offset the starting absolute offset within the file to begin reading
     * @param dst    the destination byte array to fill
     * @throws StorageException if the read range exceeds the file size, the offset 
     *                          is negative, or an arithmetic overflow occurs during bounds checking
     */
    public void read(long offset, byte[] dst) {
        try {
            long end = Math.addExact(offset, dst.length);
            if (offset < 0 || end > size) {
                throw new StorageException(StorageErrorCode.IO_ERROR, "Read exceeds mapped size: " + end + " > " + size);
            }
            MemorySegment.copy(segment, offset, MemorySegment.ofArray(dst), 0, dst.length);
        } catch (ArithmeticException e) {
            throw new StorageException(StorageErrorCode.IO_ERROR, "Read offset overflow", e);
        }
    }

    /**
     * Returns the underlying {@link MemorySegment} representing the mapped file.
     * This allows for more granular or primitive-based reads.
     *
     * @return the raw memory segment
     */
    public MemorySegment getSegment() {
        return segment;
    }

    /**
     * Returns the total size of the mapped file in bytes.
     *
     * @return the file size
     */
    public long getSize() {
        return size;
    }

    /**
     * Closes the reader, unmapping the file and releasing the associated memory budget.
     * Once closed, the memory segment is invalidated and cannot be accessed.
     */
    @Override
    public void close() {
        try {
            if (arena != null) arena.close();
        } finally {
            if (budget != null && componentName != null) budget.deallocate(componentName, size);
        }
    }
}
