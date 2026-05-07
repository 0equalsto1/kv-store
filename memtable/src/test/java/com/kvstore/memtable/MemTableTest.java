package com.kvstore.memtable;

import com.kvstore.core.MemoryBudget;
import com.kvstore.core.StorageException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.*;

class MemTableTest {
    private MemoryBudget budget;
    private MemTable memTable;

    @BeforeEach
    void setUp() {
        budget = new MemoryBudget(1024 * 1024); // 1MB budget
        memTable = new MemTableImpl(budget);
    }

    @Test
    void shouldPutAndGet() {
        byte[] key = "key1".getBytes();
        byte[] value = "value1".getBytes();

        memTable.put(key, value);
        Optional<byte[]> result = memTable.get(key);

        assertThat(result).isPresent();
        assertThat(result.get()).isEqualTo(value);
    }

    @Test
    void shouldTrackBudget() {
        byte[] key = new byte[10];
        byte[] value = new byte[90];
        // Expected size = 64 (overhead) + 10 (key) + 90 (value) = 164
        memTable.put(key, value);

        assertThat(memTable.sizeInBytes()).isEqualTo(164);
        assertThat(budget.getBytesUsed()).isEqualTo(164);
        assertThat(budget.getComponentUsage("memtable")).isEqualTo(164);

        memTable.clear();
        assertThat(memTable.sizeInBytes()).isEqualTo(0);
        assertThat(budget.getBytesUsed()).isEqualTo(0);
    }

    @Test
    void shouldEnforceBudgetLimit() {
        MemoryBudget smallBudget = new MemoryBudget(100);
        MemTable smallTable = new MemTableImpl(smallBudget);

        byte[] key = new byte[10];
        byte[] value = new byte[50];
        // 64 + 10 + 50 = 124 > 100
        assertThatThrownBy(() -> smallTable.put(key, value)).isInstanceOf(StorageException.class);
    }

    @Test
    void shouldProvideSnapshot() {
        memTable.put("a".getBytes(), "1".getBytes());
        memTable.put("b".getBytes(), "2".getBytes());

        Map<byte[], byte[]> snapshot = memTable.immutableSnapshot();
        assertThat(snapshot.size()).isEqualTo(2);
        
        // Verify sorting (a before b)
        byte[][] keys = snapshot.keySet().toArray(new byte[0][]);
        assertThat(keys[0]).isEqualTo("a".getBytes());
        assertThat(keys[1]).isEqualTo("b".getBytes());
    }
}
