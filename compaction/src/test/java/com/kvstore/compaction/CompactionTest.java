package com.kvstore.compaction;

import com.kvstore.cache.BlockCache;
import com.kvstore.cache.LruBlockCache;
import com.kvstore.core.MemoryBudget;
import com.kvstore.memtable.MemTable;
import com.kvstore.memtable.MemTableImpl;
import com.kvstore.sstable.ReadResult;
import com.kvstore.sstable.ReadStatus;
import com.kvstore.sstable.SstableReader;
import com.kvstore.sstable.SstableWriter;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.*;

class CompactionTest {
    @TempDir
    Path tempDir;

    private MemoryBudget budget;
    private BlockCache blockCache;
    private SstableWriter writer;
    private CompactionEngine engine;

    @BeforeEach
    void setUp() {
        budget = new MemoryBudget(100 * 1024 * 1024);
        blockCache = new LruBlockCache(1024 * 1024, budget);
        writer = new SstableWriter(budget);
        engine = new CompactionEngine(budget);
    }

    @Test
    void shouldMergeSstables() throws IOException {
        // SST1 (Oldest): key1=v1_old, key2=v2
        MemTable m1 = new MemTableImpl(budget);
        m1.put("key1".getBytes(), "v1_old".getBytes());
        m1.put("key2".getBytes(), "v2".getBytes());
        Path p1 = tempDir.resolve("oldest.sst");
        writer.flush(m1, p1);

        // SST2 (Newest): key1=v1_new, key3=v3
        MemTable m2 = new MemTableImpl(budget);
        m2.put("key1".getBytes(), "v1_new".getBytes());
        m2.put("key3".getBytes(), "v3".getBytes());
        Path p2 = tempDir.resolve("newest.sst");
        writer.flush(m2, p2);

        // Inputs: NEWEST to OLDEST
        try (SstableReader r1 = new SstableReader(p2, budget, blockCache);
             SstableReader r2 = new SstableReader(p1, budget, blockCache)) {
            
            Path compacted = engine.compact(List.of(r1, r2), tempDir, "compacted.sst", false);
            
            try (SstableReader result = new SstableReader(compacted, budget, blockCache)) {
                // key1 should be v1_new
                ReadResult res1 = result.get("key1".getBytes());
                assertThat(res1.status()).isEqualTo(ReadStatus.FOUND);
                assertThat(res1.value()).isEqualTo("v1_new".getBytes());

                // key2 should be v2
                ReadResult res2 = result.get("key2".getBytes());
                assertThat(res2.status()).isEqualTo(ReadStatus.FOUND);
                assertThat(res2.value()).isEqualTo("v2".getBytes());

                // key3 should be v3
                ReadResult res3 = result.get("key3".getBytes());
                assertThat(res3.status()).isEqualTo(ReadStatus.FOUND);
                assertThat(res3.value()).isEqualTo("v3".getBytes());
            }
        }
    }

    @Test
    void shouldRemoveTombstonesOnMajorCompaction() throws IOException {
        MemTable m1 = new MemTableImpl(budget);
        m1.put("key1".getBytes(), "val1".getBytes());
        Path p1 = tempDir.resolve("base.sst");
        writer.flush(m1, p1);

        MemTable m2 = new MemTableImpl(budget);
        m2.delete("key1".getBytes());
        Path p2 = tempDir.resolve("delete.sst");
        writer.flush(m2, p2);

        try (SstableReader r1 = new SstableReader(p2, budget, blockCache);
             SstableReader r2 = new SstableReader(p1, budget, blockCache)) {
            
            // Major compaction
            Path compacted = engine.compact(List.of(r1, r2), tempDir, "major.sst", true);
            
            try (SstableReader result = new SstableReader(compacted, budget, blockCache)) {
                // key1 should be physically gone
                assertThat(result.get("key1".getBytes()).status()).isEqualTo(ReadStatus.NOT_FOUND);
            }
        }
    }
}
