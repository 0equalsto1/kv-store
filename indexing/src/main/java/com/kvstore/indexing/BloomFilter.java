package com.kvstore.indexing;

import com.kvstore.core.MemoryBudget;
import com.kvstore.core.StorageErrorCode;
import com.kvstore.core.StorageException;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.VarHandle;

/**
 * A probabilistic data structure used to test whether a key is a member of a set.
 * 
 * <p>In an LSM-tree, {@code BloomFilter}s are stored in each SSTable's metadata. 
 * Before performing an expensive disk seek to find a key, the engine checks the 
 * Bloom Filter. If it returns {@code false}, the key is definitely not in the 
 * table, and a disk read is avoided. If it returns {@code true}, the key 
 * <i>might</i> be in the table, and the engine proceeds with the search.</p>
 *
 * <p><b>Implementation Details:</b>
 * This implementation uses Java's Foreign Function & Memory (FFM) API for 
 * high-performance, off-heap memory management. It utilizes {@link VarHandle} 
 * for atomic bit manipulations, ensuring thread safety during concurrent 
 * insertions (e.g., during SSTable creation).</p>
 */
public class BloomFilter implements AutoCloseable {
    /**
     * Name used for registration in the {@link MemoryBudget} tracker.
     */
    private static final String COMPONENT_NAME = "bloomFilter";
    
    /**
     * VarHandle for atomic access to 64-bit blocks of the bitset.
     */
    private static final VarHandle LONG_VAR_HANDLE = ValueLayout.JAVA_LONG.varHandle();
    
    /**
     * The raw memory segment containing the bitset.
     */
    private final MemorySegment segment;

    /**
     * Total number of bits in the filter.
     */
    private final long numBits;

    /**
     * The number of hash functions to apply for each key.
     */
    private final int numHashFunctions;

    /**
     * The arena that owns the off-heap memory (null if wrapped from existing memory).
     */
    private final Arena arena;

    /**
     * The memory budget used for tracking (null if wrapped).
     */
    private final MemoryBudget budget;

    /**
     * Creates a new off-heap {@code BloomFilter} with optimal sizing.
     * 
     * <p>This constructor calculates the optimal number of bits and hash functions 
     * based on the expected number of insertions and the desired false positive 
     * probability (FPP).</p>
     *
     * @param expectedInsertions the number of keys expected to be added to the filter
     * @param fpp                the desired false positive probability (e.g., 0.01 for 1%)
     * @param budget             the {@link MemoryBudget} for tracking off-heap allocation
     * @throws StorageException if the memory budget is exceeded
     */
    public BloomFilter(long expectedInsertions, double fpp, MemoryBudget budget) {
        this.budget = budget;
        this.numBits = optimalNumOfBits(expectedInsertions, fpp);
        this.numHashFunctions = optimalNumOfHashFunctions(expectedInsertions, numBits);
        
        // Align to 8 bytes (64 bits) to ensure atomic LONG access is efficient.
        long numBytes = (numBits + 63) / 64 * 8;
        budget.registerComponent(COMPONENT_NAME);
        budget.allocate(COMPONENT_NAME, numBytes);
        
        this.arena = Arena.ofShared();
        this.segment = arena.allocate(numBytes, 8); // Explicit 8-byte alignment
    }

    /**
     * Wraps an existing {@link MemorySegment} as a {@code BloomFilter}.
     * 
     * <p>This is typically used when loading an existing SSTable from disk, where 
     * the filter's data is already mapped into memory.</p>
     *
     * @param segment          the memory segment containing the filter's bitset
     * @param numBits          the total number of bits in the filter
     * @param numHashFunctions the number of hash functions used
     */
    public BloomFilter(MemorySegment segment, long numBits, int numHashFunctions) {
        this.segment = segment;
        this.numBits = numBits;
        this.numHashFunctions = numHashFunctions;
        this.arena = null;
        this.budget = null;
    }

    /**
     * Adds a key to the Bloom Filter.
     * 
     * <p>This method computes multiple hashes of the key using {@link MurmurHash3} 
     * and sets the corresponding bits in the bitset using atomic operations.</p>
     *
     * @param key the byte array key to add; must not be null
     */
    public void add(byte[] key) {
        long[] hashes = MurmurHash3.hash128(key, 0);
        long h1 = hashes[0];
        long h2 = hashes[1];
        
        // Use double hashing to simulate multiple hash functions efficiently.
        for (int i = 0; i < numHashFunctions; i++) {
            long combinedHash = h1 + (long) i * h2;
            setBitAtomic((combinedHash & Long.MAX_VALUE) % numBits);
        }
    }

    /**
     * Tests whether a key might be in the set.
     * 
     * <p>If any of the bits corresponding to the key's hashes are not set, 
     * this method returns {@code false} (definitely not present). Otherwise, 
     * it returns {@code true} (might be present).</p>
     *
     * @param key the byte array key to test; must not be null
     * @return {@code true} if the key might be in the set, {@code false} if it is definitely not.
     */
    public boolean mightContain(byte[] key) {
        long[] hashes = MurmurHash3.hash128(key, 0);
        long h1 = hashes[0];
        long h2 = hashes[1];
        
        for (int i = 0; i < numHashFunctions; i++) {
            long combinedHash = h1 + (long) i * h2;
            if (!getBit((combinedHash & Long.MAX_VALUE) % numBits)) {
                return false;
            }
        }
        return true;
    }

    /**
     * Sets a bit in the bitset atomically using a Compare-And-Swap (CAS) loop.
     * 
     * <p>This is necessary because multiple threads might attempt to set different 
     * bits within the same 64-bit long block simultaneously. A simple non-atomic 
     * OR operation would lead to lost updates.</p>
     *
     * @param bitIndex the 0-based index of the bit to set
     */
    private void setBitAtomic(long bitIndex) {
        long byteOffset = (bitIndex / 64) * 8;
        long bitMask = 1L << (bitIndex % 64);
        
        long current;
        do {
            // Read the current 64-bit block using volatile semantics.
            current = (long) LONG_VAR_HANDLE.getVolatile(segment, byteOffset);
            // Attempt to update the block with the new bit set. 
            // If another thread modified it in the meantime, retry.
        } while (!(boolean) LONG_VAR_HANDLE.compareAndSet(segment, byteOffset, current, current | bitMask));
    }

    /**
     * Checks if a bit is set.
     *
     * @param bitIndex the 0-based index of the bit to check
     * @return {@code true} if the bit is set, {@code false} otherwise
     */
    private boolean getBit(long bitIndex) {
        long byteOffset = (bitIndex / 64) * 8;
        long bitMask = 1L << (bitIndex % 64);
        // Use volatile read to ensure we see the most recent updates from other threads.
        long current = (long) LONG_VAR_HANDLE.getVolatile(segment, byteOffset);
        return (current & bitMask) != 0;
    }

    /**
     * Calculates the optimal number of bits for a Bloom Filter.
     * Formula: m = -n * ln(p) / (ln(2)^2)
     *
     * @param n expected insertions
     * @param p desired false positive probability
     * @return optimal number of bits
     */
    private static long optimalNumOfBits(long n, double p) {
        return (long) (-n * Math.log(p) / (Math.log(2) * Math.log(2)));
    }

    /**
     * Calculates the optimal number of hash functions.
     * Formula: k = (m/n) * ln(2)
     *
     * @param n expected insertions
     * @param m total bits
     * @return optimal number of hash functions
     */
    private static int optimalNumOfHashFunctions(long n, long m) {
        return Math.max(1, (int) Math.round((double) m / n * Math.log(2)));
    }

    /**
     * Closes the Bloom Filter and releases its resources.
     * 
     * <p>If the filter owns its memory (i.e., it was created from scratch), 
     * the {@link Arena} is closed and the memory budget is deallocated.</p>
     */
    @Override
    public void close() {
        if (arena != null) {
            arena.close();
        }
        if (budget != null) {
            long numBytes = (numBits + 63) / 64 * 8;
            budget.deallocate(COMPONENT_NAME, numBytes);
        }
    }
}
