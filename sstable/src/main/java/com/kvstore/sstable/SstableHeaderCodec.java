package com.kvstore.sstable;

import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.nio.ByteOrder;

/**
 * Utility class for encoding and decoding {@link SstableHeader} objects
 * into binary format using {@link MemorySegment}.
 * <p>
 * This codec ensures consistent serialization using Big Endian byte order
 * to maintain cross-platform compatibility of SSTable files.
 */
public class SstableHeaderCodec {
    // SSTables should always use Big Endian for cross-platform stability
    private static final ValueLayout.OfInt JAVA_INT_BE = ValueLayout.JAVA_INT_UNALIGNED.withOrder(ByteOrder.BIG_ENDIAN);
    private static final ValueLayout.OfLong JAVA_LONG_BE = ValueLayout.JAVA_LONG_UNALIGNED.withOrder(ByteOrder.BIG_ENDIAN);

    /**
     * Serializes an {@link SstableHeader} into the specified {@link MemorySegment}.
     *
     * @param header The header object to encode.
     * @param destination The memory segment where the header will be written.
     * @param offset The offset within the destination segment to start writing.
     */
    public static void encode(SstableHeader header, MemorySegment destination, long offset) {
        long currentOffset = offset;
        destination.set(JAVA_INT_BE, currentOffset, header.magic());
        currentOffset += 4;
        destination.set(ValueLayout.JAVA_BYTE, currentOffset++, header.version());
        destination.set(JAVA_LONG_BE, currentOffset, header.keyCount());
        currentOffset += 8;
        destination.set(JAVA_LONG_BE, currentOffset, header.indexOffset());
        currentOffset += 8;
        destination.set(JAVA_LONG_BE, currentOffset, header.dataOffset());
        currentOffset += 8;
        destination.set(JAVA_LONG_BE, currentOffset, header.createdTime());
        currentOffset += 8;
        destination.set(JAVA_INT_BE, currentOffset, (int) header.checksum());
    }

    /**
     * Deserializes an {@link SstableHeader} from the specified {@link MemorySegment}.
     *
     * @param source The memory segment containing the serialized header.
     * @param offset The offset within the source segment where the header begins.
     * @return A new {@link SstableHeader} instance populated from the source data.
     */
    public static SstableHeader decode(MemorySegment source, long offset) {
        long currentOffset = offset;
        int magic = source.get(JAVA_INT_BE, currentOffset);
        currentOffset += 4;
        byte version = source.get(ValueLayout.JAVA_BYTE, currentOffset++);
        long keyCount = source.get(JAVA_LONG_BE, currentOffset);
        currentOffset += 8;
        long indexOffset = source.get(JAVA_LONG_BE, currentOffset);
        currentOffset += 8;
        long dataOffset = source.get(JAVA_LONG_BE, currentOffset);
        currentOffset += 8;
        long createdTime = source.get(JAVA_LONG_BE, currentOffset);
        currentOffset += 8;
        int checksum = source.get(JAVA_INT_BE, currentOffset);

        return new SstableHeader(magic, version, keyCount, indexOffset, dataOffset, createdTime, checksum & 0xFFFFFFFFL);
    }
}
