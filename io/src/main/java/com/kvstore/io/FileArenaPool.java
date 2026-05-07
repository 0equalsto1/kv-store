package com.kvstore.io;

import com.kvstore.core.MemoryBudget;
import com.kvstore.core.StorageErrorCode;
import com.kvstore.core.StorageException;

import java.lang.foreign.MemorySegment;
import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Manages a pool of {@link MappedFileReader} instances to ensure efficient, shared access 
 * to memory-mapped files. 
 * 
 * <p>In an LSM-tree, multiple components (like Bloom Filters, Sparse Indexes, and the 
 * Search Engine) often need to read from the same SSTable file. To avoid redundant 
 * file mappings and ensure that resources are managed centrally, this pool maintains 
 * a single {@code MappedFileReader} per file path.</p>
 *
 * <p>The pool provides:
 * <ul>
 *   <li><b>Resource Sharing:</b> Multiple callers receive a proxy to the same 
 *       underlying mapping.</li>
 *   <li><b>Lifecycle Management:</b> Centralized closing of all mappings during 
 *       engine shutdown.</li>
 *   <li><b>Safety:</b> The {@link UncloseableReader} proxy prevents individual 
 *       components from closing a shared mapping prematurely.</li>
 * </ul></p>
 */
public class FileArenaPool implements AutoCloseable {
    /**
     * The global memory budget used by all readers in this pool.
     */
    private final MemoryBudget budget;

    /**
     * Cache of active readers indexed by their file path.
     */
    private final Map<Path, MappedFileReader> readers = new ConcurrentHashMap<>();

    /**
     * Flag indicating if the pool has been closed.
     */
    private volatile boolean closed = false;

    /**
     * Constructs a {@code FileArenaPool} with the specified memory budget.
     *
     * @param budget the {@link MemoryBudget} for tracking all mapped file allocations
     */
    public FileArenaPool(MemoryBudget budget) {
        this.budget = budget;
    }

    /**
     * Retrieves a {@link MappedFileReader} for the given path, creating and mapping 
     * it if it's not already in the pool.
     * 
     * <p>The returned reader is an {@link UncloseableReader} proxy. This ensures 
     * that even if a caller attempts to close it, the underlying shared mapping 
     * remains active for other consumers.</p>
     *
     * @param path the {@link Path} to the file to map
     * @return a shared, uncloseable {@link MappedFileReader} instance
     * @throws StorageException if the pool is closed or if an I/O error occurs 
     *                          during file mapping
     */
    public MappedFileReader getReader(Path path) {
        if (closed) {
            throw new StorageException(StorageErrorCode.ENGINE_CLOSED, "FileArenaPool is closed");
        }
        
        MappedFileReader reader = readers.computeIfAbsent(path, p -> {
            // Note: computeIfAbsent is atomic for the same key, but doesn't prevent 
            // concurrent creation for different keys while close() is running.
            if (closed) {
                throw new StorageException(StorageErrorCode.ENGINE_CLOSED, "FileArenaPool closed during reader creation");
            }
            return new MappedFileReader(p, budget);
        });

        return new UncloseableReader(reader);
    }

    /**
     * Closes the pool and all associated {@link MappedFileReader} instances.
     * 
     * <p>After this method is called, any attempts to create new readers will 
     * fail, and any existing segments obtained from readers will become invalid 
     * as the underlying arenas are closed.</p>
     */
    @Override
    public synchronized void close() {
        if (closed) return;
        closed = true;
        
        for (MappedFileReader reader : readers.values()) {
            try {
                reader.close();
            } catch (Exception ignored) {
                // Ignore errors during mass closing; we want to attempt to close all readers.
            }
        }
        readers.clear();
    }

    /**
     * A proxy implementation of {@link MappedFileReader} that ignores {@link #close()} calls.
     * 
     * <p>This ensures that shared readers managed by the {@link FileArenaPool} 
     * are only closed when the pool itself is closed, preventing accidental 
     * unmapping by a single consumer.</p>
     */
    private static class UncloseableReader extends MappedFileReader {
        /**
         * The actual shared reader to which operations are delegated.
         */
        private final MappedFileReader delegate;

        /**
         * Constructs a new proxy for the given delegate.
         * 
         * <p>Uses the protected no-args constructor of {@code MappedFileReader} 
         * to avoid redundant budget allocations or file mappings.</p>
         *
         * @param delegate the shared reader to wrap
         */
        private UncloseableReader(MappedFileReader delegate) {
            super(); 
            this.delegate = delegate;
        }

        /**
         * {@inheritDoc}
         * @param offset the starting absolute offset within the file
         * @param dst    the destination byte array
         */
        @Override
        public void read(long offset, byte[] dst) {
            delegate.read(offset, dst);
        }

        /**
         * {@inheritDoc}
         * @return the raw memory segment from the delegate
         */
        @Override
        public MemorySegment getSegment() {
            return delegate.getSegment();
        }

        /**
         * {@inheritDoc}
         * @return the size of the delegate's mapping
         */
        @Override
        public long getSize() {
            return delegate.getSize();
        }

        /**
         * No-op implementation of close. 
         * Only the {@link FileArenaPool} has the authority to close the underlying delegate.
         */
        @Override
        public void close() {
            // No-op: Only the pool can close the shared reader
        }
    }
}
