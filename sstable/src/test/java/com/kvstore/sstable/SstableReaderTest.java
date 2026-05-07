package com.kvstore.sstable;

import com.kvstore.cache.BlockCache;
import com.kvstore.cache.LruBlockCache;
import com.kvstore.core.MemoryBudget;
import com.kvstore.memtable.MemTable;
import com.kvstore.memtable.MemTableImpl;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.*;

class SstableReaderTest {
    @TempDir
    Path tempDir;

    private MemoryBudget budget;
    private BlockCache blockCache;
    private SstableWriter writer;

    @BeforeEach
    void setUp() {
        budget = new MemoryBudget(100 * 1024 * 1024);
        blockCache = new LruBlockCache(1024 * 1024, budget);
        writer = new SstableWriter(budget);
    }

    @Test
    void shouldReadFlushOutput() throws IOException {
        MemTable memTable = new MemTableImpl(budget);
        memTable.put("key1".getBytes(), "val1".getBytes());
        memTable.put("key2".getBytes(), "val2".getBytes());
        memTable.delete("key3".getBytes());

        Path path = tempDir.resolve("test.sst");
        writer.flush(memTable, path);

        try (SstableReader reader = new SstableReader(path, budget, blockCache)) {
            ReadResult r1 = reader.get("key1".getBytes());
            assertThat(r1.status()).isEqualTo(ReadStatus.FOUND);
            assertThat(r1.value()).isEqualTo("val1".getBytes());

            ReadResult r2 = reader.get("key2".getBytes());
            assertThat(r2.status()).isEqualTo(ReadStatus.FOUND);
            assertThat(r2.value()).isEqualTo("val2".getBytes());

            ReadResult r3 = reader.get("key3".getBytes());
            assertThat(r3.status()).isEqualTo(ReadStatus.DELETED);

            ReadResult r4 = reader.get("missing".getBytes());
            assertThat(r4.status()).isEqualTo(ReadStatus.NOT_FOUND);
        }
    }
}
