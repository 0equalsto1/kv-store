package com.kvstore.sstable;

import com.kvstore.core.MemoryBudget;
import com.kvstore.memtable.MemTable;
import com.kvstore.memtable.MemTableImpl;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.*;

class SstableWriterTest {
    @TempDir
    Path tempDir;

    private MemoryBudget budget;
    private SstableWriter writer;

    @BeforeEach
    void setUp() {
        budget = new MemoryBudget(100 * 1024 * 1024);
        writer = new SstableWriter(budget);
    }

    @Test
    void shouldFlushMemTableToSstable() throws IOException {
        MemTable memTable = new MemTableImpl(budget);
        memTable.put("key1".getBytes(), "value1".getBytes());
        memTable.put("key2".getBytes(), "value2".getBytes());

        Path sstablePath = tempDir.resolve("test.sst");
        writer.flush(memTable, sstablePath);

        assertThat(sstablePath.toFile().exists()).isTrue();
        assertThat(sstablePath.toFile().length() > SstableHeader.HEADER_SIZE).isTrue();
        
        // We'll verify content in the next story when we have SstableReader.
        // For now, checking file size and budget tracking.
        assertThat(budget.getComponentUsage("sstable")).as("Budget should be released after flush").isEqualTo(0);
    }
}
