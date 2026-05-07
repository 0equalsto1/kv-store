package com.kvstore.memtable;

import com.kvstore.core.MemoryBudget;
import org.junit.jupiter.api.Test;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

class MemTableConcurrencyTest {
    @Test
    void shouldHandleConcurrentWrites() throws InterruptedException {
        MemoryBudget budget = new MemoryBudget(100 * 1024 * 1024);
        MemTable memTable = new MemTableImpl(budget);
        int numThreads = 16;
        int entriesPerThread = 1000;
        ExecutorService executor = Executors.newFixedThreadPool(numThreads);

        for (int i = 0; i < numThreads; i++) {
            final int threadId = i;
            executor.submit(() -> {
                for (int j = 0; j < entriesPerThread; j++) {
                    byte[] key = ("key-" + threadId + "-" + j).getBytes();
                    byte[] value = ("value-" + j).getBytes();
                    memTable.put(key, value);
                }
            });
        }

        executor.shutdown();
        executor.awaitTermination(10, TimeUnit.SECONDS);

        assertThat(memTable.immutableSnapshot().size()).isEqualTo(numThreads * entriesPerThread);
    }
}
