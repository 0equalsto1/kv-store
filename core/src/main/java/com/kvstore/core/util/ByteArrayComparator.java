package com.kvstore.core.util;

import java.util.Comparator;

/**
 * A lexicographical comparator for byte arrays that performs unsigned byte comparison.
 * <p>
 * This comparator is essential for maintaining the sorted order of keys in the LSM-tree.
 * Standard Java {@code byte} comparison is signed (-128 to 127), which can lead to
 * unexpected sorting behavior (e.g., 0xFF being less than 0x01). This implementation
 * treats each byte as an unsigned value (0 to 255) to ensure a natural lexicographical
 * ordering that is consistent across different platforms and storage formats.
 */
public class ByteArrayComparator implements Comparator<byte[]> {
    /**
     * Singleton instance of the comparator to avoid unnecessary allocations and 
     * garbage collection pressure. Since the comparator is stateless and thread-safe,
     * a single instance can be shared across the entire application.
     */
    public static final ByteArrayComparator INSTANCE = new ByteArrayComparator();

    /**
     * Compares two byte arrays lexicographically using unsigned byte comparison.
     * <p>
     * The comparison logic follows these steps:
     * <ol>
     *     <li>Checks for identity equality (a == b).</li>
     *     <li>Handles null checks, treating null as smaller than any non-null array.</li>
     *     <li>Iterates through the common length of both arrays, comparing bytes at each index.</li>
     *     <li>Uses {@link Byte#toUnsignedInt(byte)} to ensure unsigned comparison.</li>
     *     <li>If all bytes in the common length are equal, the shorter array is considered smaller.</li>
     * </ol>
     *
     * @param a the first byte array to be compared; may be null.
     * @param b the second byte array to be compared; may be null.
     * @return a negative integer, zero, or a positive integer as the first argument
     *         is less than, equal to, or greater than the second.
     */
    @Override
    public int compare(byte[] a, byte[] b) {
        if (a == b) return 0;
        if (a == null) return -1;
        if (b == null) return 1;

        int len = Math.min(a.length, b.length);
        for (int i = 0; i < len; i++) {
            int diff = Byte.toUnsignedInt(a[i]) - Byte.toUnsignedInt(b[i]);
            if (diff != 0) return diff;
        }
        return a.length - b.length;
    }
}
