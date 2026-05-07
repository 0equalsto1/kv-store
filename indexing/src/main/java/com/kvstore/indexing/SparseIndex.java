package com.kvstore.indexing;

import com.kvstore.core.util.ByteArrayComparator;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.nio.ByteOrder;
import java.util.Arrays;

/**
 * An in-memory, sparse index for an SSTable (Sorted String Table).
 * 
 * <p>In an LSM-tree, SSTables can grow very large (GBs). Reading the entire index for 
 * every query would be inefficient. A {@code SparseIndex} stores only a subset of keys 
 * (e.g., the first key of every 4KB or 64KB block) along with their absolute byte 
 * offsets within the SSTable file.</p>
 *
 * <p><b>Search Logic:</b>
 * To find a target key, the {@link #findFloorOffset(byte[])} method performs a 
 * binary search over the sparse keys to find the largest key that is less than 
 * or equal to the target. The corresponding offset points to the start of the 
 * data block where the target key <i>must</i> reside if it exists.</p>
 *
 * <p><b>Memory Layout (Off-Heap):</b>
 * The index is backed by a {@link MemorySegment} with the following layout:
 * <pre>
 * [Entry 0][Entry 1]...[Entry N][Offset 0][Offset 1]...[Offset N][Entry Count (8 bytes)]
 * 
 * Each Entry: [Key Length (4 bytes)][Key Bytes (variable)][Data Offset (8 bytes)]
 * Each Offset: [Absolute position of Entry i within the segment (8 bytes)]
 * </pre>
 */
public class SparseIndex {
    /**
     * Big-endian integer layout for key lengths.
     */
    private static final ValueLayout.OfInt JAVA_INT_BE = ValueLayout.JAVA_INT_UNALIGNED.withOrder(ByteOrder.BIG_ENDIAN);
    
    /**
     * Big-endian long layout for offsets and entry counts.
     */
    private static final ValueLayout.OfLong JAVA_LONG_BE = ValueLayout.JAVA_LONG_UNALIGNED.withOrder(ByteOrder.BIG_ENDIAN);

    /**
     * The raw memory segment containing the serialized index.
     */
    private final MemorySegment segment;

    /**
     * The total number of sparse entries in this index.
     */
    private final long entryCount;

    /**
     * The starting byte offset within the segment where the array of entry pointers begins.
     */
    private final long offsetsArrayOffset;

    /**
     * Constructs a {@code SparseIndex} from a pre-loaded memory segment.
     * 
     * <p>This constructor parses the metadata at the end of the segment to 
     * initialize the entry count and the location of the pointer array.</p>
     *
     * @param segment the {@link MemorySegment} containing the serialized sparse index; 
     *                must be at least 8 bytes long (to contain the entry count).
     * @throws IndexOutOfBoundsException if the segment is too small to contain the metadata.
     */
    public SparseIndex(MemorySegment segment) {
        this.segment = segment;
        // The last 8 bytes of the segment always store the total number of entries.
        this.entryCount = segment.get(JAVA_LONG_BE, segment.byteSize() - 8);
        // The pointer array (storing positions of entries) precedes the count.
        this.offsetsArrayOffset = segment.byteSize() - 8 - (entryCount * 8);
    }

    /**
     * Performs a binary search to find the potential starting position of a key.
     * 
     * <p>The "floor" offset is the offset of the data block associated with the 
     * largest key in the index that is still less than or equal to the {@code targetKey}.</p>
     *
     * @param targetKey the byte array key to search for
     * @return the absolute data offset within the SSTable's data section where 
     *         the key might be found, or -1 if the index is empty or the target 
     *         key precedes the first entry in the index.
     */
    public long findFloorOffset(byte[] targetKey) {
        if (entryCount == 0) return -1;

        long low = 0;
        long high = entryCount - 1;
        long bestOffset = -1;

        while (low <= high) {
            long mid = (low + high) >>> 1;
            byte[] midKey = getEntryKey(mid);
            int cmp = ByteArrayComparator.INSTANCE.compare(midKey, targetKey);

            if (cmp <= 0) {
                // The target might be in this block or a later one.
                bestOffset = getEntryDataOffset(mid);
                low = mid + 1;
            } else {
                // The target is definitely before this block.
                high = mid - 1;
            }
        }

        return bestOffset;
    }

    /**
     * Retrieves the key for the entry at the specified index.
     *
     * @param index the logical index (0 to entryCount-1)
     * @return the byte array containing the key
     */
    private byte[] getEntryKey(long index) {
        // 1. Get the position of the entry from the pointer array.
        long entryPos = segment.get(JAVA_LONG_BE, offsetsArrayOffset + (index * 8));
        // 2. Read the key length (first 4 bytes of the entry).
        int keyLen = segment.get(JAVA_INT_BE, entryPos);
        // 3. Copy the key bytes into a new heap array.
        byte[] key = new byte[keyLen];
        MemorySegment.copy(segment, entryPos + 4, MemorySegment.ofArray(key), 0, keyLen);
        return key;
    }

    /**
     * Retrieves the data offset for the entry at the specified index.
     *
     * @param index the logical index (0 to entryCount-1)
     * @return the long offset into the SSTable file
     */
    private long getEntryDataOffset(long index) {
        // 1. Get the position of the entry.
        long entryPos = segment.get(JAVA_LONG_BE, offsetsArrayOffset + (index * 8));
        // 2. Read the key length to calculate where the offset field begins.
        int keyLen = segment.get(JAVA_INT_BE, entryPos);
        // 3. Read the 8-byte offset following the key: [keyLen(4) | key(var) | offset(8)]
        return segment.get(JAVA_LONG_BE, entryPos + 4 + keyLen);
    }
}
