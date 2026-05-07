package com.kvstore.compaction;

import com.kvstore.cache.BlockCache;
import com.kvstore.cache.LruBlockCache;
import com.kvstore.core.MemoryBudget;
import com.kvstore.memtable.MemTable;
import com.kvstore.memtable.MemTableImpl;
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

class TombstoneRemovalTest {
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
    void shouldKeepTombstoneOnMinorCompaction() throws IOException {
        MemTable m1 = new MemTableImpl(budget);
        m1.delete("deleted-key".getBytes());
        Path p1 = tempDir.resolve("delete.sst");
        writer.flush(m1, p1);

        try (SstableReader r1 = new SstableReader(p1, budget, blockCache)) {
            // Minor compaction (isMajor = false)
            Path compacted = engine.compact(List.of(r1), tempDir, "minor.sst", false);
            
            try (SstableReader result = new SstableReader(compacted, budget, blockCache)) {
                // Tombstone should still be there to prevent old data from resurfacing
                assertThat(result.get("deleted-key".getBytes()).status()).isEqualTo(ReadStatus.DELETED);
            }
        }
    }

    @Test
    void shouldRemoveTombstoneOnMajorCompaction() throws IOException {
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
            
            // Major compaction (isMajor = true)
            Path compacted = engine.compact(List.of(r1, r2), tempDir, "major.sst", true);
            
            try (SstableReader result = new SstableReader(compacted, budget, blockCache)) {
                // key1 should be physically gone
                assertThat(result.get("key1".getBytes()).status()).isEqualTo(ReadStatus.NOT_FOUND);
            }
        }
    }
}
