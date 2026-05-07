package com.kvstore.indexing;

import com.kvstore.core.MemoryBudget;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.*;

class BloomFilterTest {
    private MemoryBudget budget;

    @BeforeEach
    void setUp() {
        budget = new MemoryBudget(100 * 1024 * 1024);
    }

    @Test
    void shouldMaintainMembership() {
        try (BloomFilter filter = new BloomFilter(1000, 0.01, budget)) {
            byte[] k1 = "key1".getBytes();
            byte[] k2 = "key2".getBytes();
            byte[] k3 = "key3".getBytes();

            filter.add(k1);
            filter.add(k2);

            assertThat(filter.mightContain(k1)).isTrue();
            assertThat(filter.mightContain(k2)).isTrue();
            assertThat(filter.mightContain(k3)).isFalse();
        }
    }

    @Test
    void shouldTrackBudget() {
        long initial = budget.getBytesUsed();
        try (BloomFilter filter = new BloomFilter(1000000, 0.01, budget)) {
            assertThat(budget.getBytesUsed() > initial).isTrue();
            assertThat(budget.getComponentUsage("bloomFilter") > 0).isTrue();
        }
        assertThat(budget.getComponentUsage("bloomFilter")).isEqualTo(0);
    }
}
