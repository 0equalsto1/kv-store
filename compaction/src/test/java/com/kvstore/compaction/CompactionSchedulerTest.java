package com.kvstore.compaction;

import com.kvstore.cache.BlockCache;
import com.kvstore.cache.LruBlockCache;
import com.kvstore.core.MemoryBudget;
import com.kvstore.memtable.MemTable;
import com.kvstore.memtable.MemTableImpl;
import com.kvstore.concurrency.BackpressureSignal;
import com.kvstore.concurrency.WriteBackpressure;
import com.kvstore.sstable.SstableReader;
import com.kvstore.sstable.SstableWriter;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.*;

class CompactionSchedulerTest {
    @TempDir
    Path tempDir;

    private MemoryBudget budget;
    private BlockCache blockCache;
    private SstableWriter writer;
    private CompactionEngine engine;
    private WriteBackpressure backpressure;

    @BeforeEach
    void setUp() {
        budget = new MemoryBudget(100 * 1024 * 1024);
        blockCache = new LruBlockCache(1024 * 1024, budget);
        writer = new SstableWriter(budget);
        engine = new CompactionEngine(budget);
        backpressure = new WriteBackpressure(1000);
    }

    @Test
    void shouldTriggerCompactionWhenThresholdReached() throws IOException, InterruptedException {
        List<SstableReader> sstables = new ArrayList<>();
        try {
            for (int i = 0; i < 3; i++) {
                MemTable m = new MemTableImpl(budget);
                m.put(("key" + i).getBytes(), "val".getBytes());
                Path p = tempDir.resolve("test" + i + ".sst");
                writer.flush(m, p);
                sstables.add(new SstableReader(p, budget, blockCache));
            }

            CountDownLatch latch = new CountDownLatch(1);
            CompactionScheduler.SstableProvider provider = new CompactionScheduler.SstableProvider() {
                @Override
                public List<SstableReader> getSstablesForCompaction() {
                    return sstables;
                }

                @Override
                public int getTotalSstableCount() {
                    return sstables.size();
                }

                @Override
                public void onCompactionFinished(Path newSstable, List<SstableReader> oldSstables) {
                    latch.countDown();
                }

                @Override
                public BackpressureSignal getBackpressureSignal() {
                    return backpressure;
                }
            };

            try (CompactionScheduler scheduler = new CompactionScheduler(engine, tempDir, 3, provider)) {
                scheduler.start();
                assertThat(latch.await(10, TimeUnit.SECONDS)).as("Compaction should have been triggered and completed").isTrue();
            }
        } finally {
            for (SstableReader r : sstables) {
                try { r.close(); } catch (Exception ignored) {}
            }
        }
    }
}
