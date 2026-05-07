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
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.*;

class CompactionStressTest {
    @TempDir
    Path tempDir;

    private MemoryBudget budget;
    private BlockCache blockCache;
    private SstableWriter writer;
    private CompactionEngine engine;

    @BeforeEach
    void setUp() {
        budget = new MemoryBudget(200 * 1024 * 1024);
        blockCache = new LruBlockCache(10 * 1024 * 1024, budget);
        writer = new SstableWriter(budget);
        engine = new CompactionEngine(budget);
    }

    @Test
    void shouldHandleConcurrentCompactionTasks() throws IOException, InterruptedException {
        int numSstables = 20;
        List<SstableReader> sstables = new ArrayList<>();
        
        // Create 20 small SSTables
        for (int i = 0; i < numSstables; i++) {
            MemTable m = new MemTableImpl(budget);
            m.put(("key" + i).getBytes(), ("val" + i).getBytes());
            Path p = tempDir.resolve("stress-" + i + ".sst");
            writer.flush(m, p);
            sstables.add(new SstableReader(p, budget, blockCache));
        }

        ExecutorService executor = Executors.newFixedThreadPool(5);
        AtomicInteger successCount = new AtomicInteger(0);
        
        try {
            for (int i = 0; i < 10; i++) {
                final int index = i;
                executor.execute(() -> {
                    try {
                        // Compact pairs of SSTables
                        List<SstableReader> subList = List.of(sstables.get(index * 2), sstables.get(index * 2 + 1));
                        Path result = engine.compact(subList, tempDir, "compacted-" + index + ".sst", false);
                        
                        // Verify result
                        try (SstableReader reader = new SstableReader(result, budget, blockCache)) {
                            ReadResult res1 = reader.get(("key" + (index * 2)).getBytes());
                            assertThat(res1.status()).isEqualTo(ReadStatus.FOUND);
                            ReadResult res2 = reader.get(("key" + (index * 2 + 1)).getBytes());
                            assertThat(res2.status()).isEqualTo(ReadStatus.FOUND);
                        }
                        successCount.incrementAndGet();
                    } catch (Exception e) {
                        e.printStackTrace();
                    }
                });
            }
        } finally {
            executor.shutdown();
            assertThat(executor.awaitTermination(30, TimeUnit.SECONDS)).isTrue();
            assertThat(successCount.get()).as("All compaction tasks should succeed").isEqualTo(10);
            for (SstableReader r : sstables) {
                try { r.close(); } catch (Exception ignored) {}
            }
        }
    }
}
