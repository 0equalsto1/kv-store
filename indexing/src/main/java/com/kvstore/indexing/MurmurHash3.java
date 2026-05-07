package com.kvstore.indexing;

import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.nio.ByteOrder;

/**
 * High-performance implementation of the MurmurHash3 algorithm (x64_128 variant).
 * 
 * <p>MurmurHash3 is a non-cryptographic hash function that provides excellent 
 * bit distribution and performance. The x64_128 variant produces a 128-bit 
 * hash value, which is particularly useful for data structures like 
 * {@link BloomFilter} to minimize collisions.</p>
 *
 * <p><b>Implementation Details:</b>
 * This version is optimized for 64-bit architectures. It processes data in 
 * 16-byte blocks using Java's Foreign Function & Memory (FFM) API for efficient 
 * unaligned memory access from byte arrays. This avoids the overhead of manual 
 * byte shifting for multi-byte reads.</p>
 *
 * <p>The algorithm consists of three main phases:
 * <ol>
 *   <li><b>Body:</b> Processing 128-bit (16-byte) blocks in a loop.</li>
 *   <li><b>Tail:</b> Handling any remaining 1-15 bytes.</li>
 *   <li><b>Finalization:</b> Mixing the result to ensure the "avalanche effect" 
 *       (where a small change in input causes a large change in output).</li>
 * </ol></p>
 */
public class MurmurHash3 {
    /**
     * Little-endian long layout for efficient 64-bit reads from memory segments.
     */
    private static final ValueLayout.OfLong JAVA_LONG_LE = ValueLayout.JAVA_LONG_UNALIGNED.withOrder(ByteOrder.LITTLE_ENDIAN);

    /**
     * Computes a 128-bit MurmurHash3 for the given byte array.
     * 
     * <p>This implementation follows the x64 128-bit variant which is optimized 
     * for 64-bit processors and provides two 64-bit halves of the result.</p>
     *
     * @param data the byte array to be hashed; must not be null
     * @param seed the initial seed value for the hash, allowing for different 
     *             hash results for the same input
     * @return a {@code long[]} of length 2 containing the 128-bit hash value
     */
    public static long[] hash128(byte[] data, int seed) {
        long h1 = seed;
        long h2 = seed;

        final long c1 = 0x87c37b91114253d5L;
        final long c2 = 0x4cf5ad432745937fL;

        int len = data.length;
        int nblocks = len / 16;

        // Create a temporary segment to use FFM for efficient long reads from the array.
        MemorySegment segment = MemorySegment.ofArray(data);

        // Body: process 16-byte blocks
        for (int i = 0; i < nblocks; i++) {
            long k1 = segment.get(JAVA_LONG_LE, i * 16L);
            long k2 = segment.get(JAVA_LONG_LE, i * 16L + 8);

            k1 *= c1;
            k1 = Long.rotateLeft(k1, 31);
            k1 *= c2;
            h1 ^= k1;

            h1 = Long.rotateLeft(h1, 27);
            h1 += h2;
            h1 = h1 * 5 + 0x52dce729;

            k2 *= c2;
            k2 = Long.rotateLeft(k2, 33);
            k2 *= c1;
            h2 ^= k2;

            h2 = Long.rotateLeft(h2, 31);
            h2 += h1;
            h2 = h2 * 5 + 0x38495ab5;
        }

        // Tail: handle remaining bytes
        long k1 = 0;
        long k2 = 0;
        int tail = nblocks * 16;

        switch (len & 15) {
            case 15: k2 ^= (long) (data[tail + 14] & 0xFF) << 48;
            case 14: k2 ^= (long) (data[tail + 13] & 0xFF) << 40;
            case 13: k2 ^= (long) (data[tail + 12] & 0xFF) << 32;
            case 12: k2 ^= (long) (data[tail + 11] & 0xFF) << 24;
            case 11: k2 ^= (long) (data[tail + 10] & 0xFF) << 16;
            case 10: k2 ^= (long) (data[tail + 9] & 0xFF) << 8;
            case 9:  k2 ^= (long) (data[tail + 8] & 0xFF);
                     k2 *= c2; k2 = Long.rotateLeft(k2, 33); k2 *= c1; h2 ^= k2;

            case 8:  k1 ^= (long) (data[tail + 7] & 0xFF) << 56;
            case 7:  k1 ^= (long) (data[tail + 6] & 0xFF) << 48;
            case 6:  k1 ^= (long) (data[tail + 5] & 0xFF) << 40;
            case 5:  k1 ^= (long) (data[tail + 4] & 0xFF) << 32;
            case 4:  k1 ^= (long) (data[tail + 3] & 0xFF) << 24;
            case 3:  k1 ^= (long) (data[tail + 2] & 0xFF) << 16;
            case 2:  k1 ^= (long) (data[tail + 1] & 0xFF) << 8;
            case 1:  k1 ^= (long) (data[tail] & 0xFF);
                     k1 *= c1; k1 = Long.rotateLeft(k1, 31); k1 *= c2; h1 ^= k1;
        }

        // Finalization
        h1 ^= len;
        h2 ^= len;

        h1 += h2;
        h2 += h1;

        h1 = fmix64(h1);
        h2 = fmix64(h2);

        h1 += h2;
        h2 += h1;

        return new long[]{h1, h2};
    }

    /**
     * Finalizing mix function for 64-bit values.
     * 
     * <p>This function ensures that even small changes in the input bits are 
     * propagated thoroughly through the result bits (avalanche effect), which 
     * is critical for the quality of the hash.</p>
     *
     * @param k the 64-bit value to be mixed
     * @return the mixed 64-bit value
     */
    private static long fmix64(long k) {
        k ^= k >>> 33;
        k *= 0xff51afd7ed558ccdL;
        k ^= k >>> 33;
        k *= 0xc4ceb9fe1a85ec53L;
        k ^= k >>> 33;
        return k;
    }
}
