package com.kvstore.indexing;

import com.kvstore.core.util.ByteArrayComparator;
import org.junit.jupiter.api.Test;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.nio.ByteOrder;

import static org.assertj.core.api.Assertions.*;

class SparseIndexTest {
    private static final ValueLayout.OfInt JAVA_INT_BE = ValueLayout.JAVA_INT_UNALIGNED.withOrder(ByteOrder.BIG_ENDIAN);
    private static final ValueLayout.OfLong JAVA_LONG_BE = ValueLayout.JAVA_LONG_UNALIGNED.withOrder(ByteOrder.BIG_ENDIAN);

    @Test
    void shouldFindFloorOffset() {
        try (Arena arena = Arena.ofConfined()) {
            // Build a manual index in memory
            // Entries: [k1:100], [k5:200], [k9:300]
            byte[] k1 = "k1".getBytes();
            byte[] k5 = "k5".getBytes();
            byte[] k9 = "k9".getBytes();

            long entry1Size = 4 + k1.length + 8;
            long entry2Size = 4 + k5.length + 8;
            long entry3Size = 4 + k9.length + 8;
            
            long totalSize = entry1Size + entry2Size + entry3Size + (3 * 8) + 8;
            MemorySegment segment = arena.allocate(totalSize);
            
            long curr = 0;
            long p1 = curr;
            segment.set(JAVA_INT_BE, curr, k1.length); curr += 4;
            MemorySegment.copy(MemorySegment.ofArray(k1), 0, segment, curr, k1.length); curr += k1.length;
            segment.set(JAVA_LONG_BE, curr, 100L); curr += 8;

            long p2 = curr;
            segment.set(JAVA_INT_BE, curr, k5.length); curr += 4;
            MemorySegment.copy(MemorySegment.ofArray(k5), 0, segment, curr, k5.length); curr += k5.length;
            segment.set(JAVA_LONG_BE, curr, 200L); curr += 8;

            long p3 = curr;
            segment.set(JAVA_INT_BE, curr, k9.length); curr += 4;
            MemorySegment.copy(MemorySegment.ofArray(k9), 0, segment, curr, k9.length); curr += k9.length;
            segment.set(JAVA_LONG_BE, curr, 300L); curr += 8;

            // Offsets array
            segment.set(JAVA_LONG_BE, curr, p1); curr += 8;
            segment.set(JAVA_LONG_BE, curr, p2); curr += 8;
            segment.set(JAVA_LONG_BE, curr, p3); curr += 8;

            // Count
            segment.set(JAVA_LONG_BE, curr, 3L);

            SparseIndex index = new SparseIndex(segment);
            
            // k0 < k1, so no floor entry exists
            assertThat(index.findFloorOffset("k0".getBytes())).isEqualTo(-1);
            
            assertThat(index.findFloorOffset("k1".getBytes())).isEqualTo(100);
            assertThat(index.findFloorOffset("k2".getBytes())).isEqualTo(100);
            assertThat(index.findFloorOffset("k5".getBytes())).isEqualTo(200);
            assertThat(index.findFloorOffset("k6".getBytes())).isEqualTo(200);
            assertThat(index.findFloorOffset("k9".getBytes())).isEqualTo(300);
            assertThat(index.findFloorOffset("kz".getBytes())).isEqualTo(300);
        }
    }
}
