package com.kvstore.memtable;

import com.kvstore.core.MemoryBudget;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.assertj.core.api.Assertions.*;

class MemTableDeleteTest {
    private MemoryBudget budget;
    private MemTable memTable;

    @BeforeEach
    void setUp() {
        budget = new MemoryBudget(1024 * 1024);
        memTable = new MemTableImpl(budget);
    }

    @Test
    void shouldDeleteKey() {
        byte[] key = "key1".getBytes();
        byte[] value = "value1".getBytes();

        memTable.put(key, value);
        assertThat(memTable.get(key)).isPresent();

        memTable.delete(key);
        assertThat(memTable.get(key).isPresent()).as("Key should be logically missing after delete").isFalse();
        
        // Internal map should still have the tombstone
        assertThat(memTable.immutableSnapshot().containsKey(key)).isTrue();
        assertThat(memTable.immutableSnapshot().get(key).length).isEqualTo(0);
    }

    @Test
    void shouldTrackBudgetForTombstones() {
        byte[] key = "key1".getBytes();
        long initialBudget = budget.getBytesUsed();
        
        memTable.delete(key);
        long sizeWithTombstone = budget.getBytesUsed() - initialBudget;
        
        // Overhead (64) + key length (4) + value length (0) = 68
        assertThat(sizeWithTombstone).isEqualTo(68);
        
        memTable.put(key, "val".getBytes());
        // Overhead (64) + key (4) + value (3) = 71
        assertThat(budget.getBytesUsed()).isEqualTo(71);
    }
}
