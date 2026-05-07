package com.kvstore.sstable;

import com.kvstore.core.MemoryBudget;
import com.kvstore.memtable.MemTable;
import com.kvstore.memtable.MemTableImpl;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

class SstableFlushStressTest {
    @TempDir
    Path tempDir;

    @Test
    void shouldHandleConcurrentFlushesWithinBudget() throws InterruptedException {
        // Budget limited to 10MB
        MemoryBudget budget = new MemoryBudget(10 * 1024 * 1024);
        SstableWriter writer = new SstableWriter(budget);
        
        int numFlushes = 5;
        ExecutorService executor = Executors.newFixedThreadPool(numFlushes);

        for (int i = 0; i < numFlushes; i++) {
            final int id = i;
            executor.submit(() -> {
                try {
                    MemTable memTable = new MemTableImpl(budget);
                    // Fill memtable with ~1MB of data
                    for (int j = 0; j < 1000; j++) {
                        memTable.put(("key-" + id + "-" + j).getBytes(), new byte[1000]);
                    }
                    writer.flush(memTable, tempDir.resolve("stress-" + id + ".sst"));
                } catch (Exception e) {
                    e.printStackTrace();
                }
            });
        }

        executor.shutdown();
        executor.awaitTermination(30, TimeUnit.SECONDS);

        assertThat(budget.getComponentUsage("sstable")).as("All sstable budget should be released").isEqualTo(0);
    }
}
