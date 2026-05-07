package com.kvstore.cache;

import com.kvstore.core.MemoryBudget;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.lang.foreign.MemorySegment;
import java.util.Optional;

import static org.assertj.core.api.Assertions.*;

class BlockCacheTest {
    private MemoryBudget budget;
    private BlockCache cache;

    @BeforeEach
    void setUp() {
        budget = new MemoryBudget(1024 * 1024);
        cache = new LruBlockCache(2000, budget); // 2KB capacity
    }

    @Test
    void shouldCacheAndEvict() {
        byte[] data1 = new byte[800];
        byte[] data2 = new byte[800];
        byte[] data3 = new byte[800];

        cache.put("sst1", 0, data1);
        cache.put("sst1", 800, data2);
        
        assertThat(cache.get("sst1", 0)).isPresent();
        assertThat(cache.get("sst1", 800)).isPresent();

        // Sum = (800+128)*2 = 1856.
        // Third put: 1856 + (800+128) = 2784 > 2000.
        cache.put("sst1", 1600, data3);

        assertThat(cache.get("sst1", 0).isPresent()).as("First entry should be evicted").isFalse();
        assertThat(cache.get("sst1", 800)).isPresent();
        assertThat(cache.get("sst1", 1600)).isPresent();
    }

    @Test
    void shouldTrackBudget() throws Exception {
        byte[] data = new byte[500];
        cache.put("sst1", 0, data);
        
        long usage = budget.getComponentUsage("cache");
        assertThat(usage >= 500).isTrue();

        cache.close();
        assertThat(budget.getComponentUsage("cache")).isEqualTo(0);
    }
}
