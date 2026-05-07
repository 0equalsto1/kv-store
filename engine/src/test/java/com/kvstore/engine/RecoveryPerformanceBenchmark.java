package com.kvstore.engine;

import com.kvstore.core.MemoryBudget;
import com.kvstore.memtable.MemTable;
import com.kvstore.memtable.MemTableImpl;
import com.kvstore.wal.WalWriter;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Benchmark for WAL recovery performance.
 */
public class RecoveryPerformanceBenchmark {
    public static void main(String[] args) throws Exception {
        // Disable verbose logging for benchmark
        Logger.getLogger("com.kvstore").setLevel(Level.WARNING);

        Path tempDir = Files.createTempDirectory("recovery-bench");
        Path walDir = tempDir.resolve("wal");
        Files.createDirectories(walDir);

        int recordCount = 1_000_000;
        byte[] key = new byte[16];
        byte[] value = new byte[100];

        // 10GB budget for benchmark
        MemoryBudget budget = new MemoryBudget(10L * 1024 * 1024 * 1024);
        
        System.out.println("Generating " + recordCount + " records in WAL...");
        long startGen = System.currentTimeMillis();
        try (WalWriter writer = new WalWriter(walDir.resolve("bench.wal"), 200 * 1024 * 1024L, budget)) {
            for (int i = 0; i < recordCount; i++) {
                writer.appendPut(key, value);
            }
        }
        long endGen = System.currentTimeMillis();
        System.out.println("Generation took: " + (endGen - startGen) + "ms");

        // 2. Benchmark Recovery
        MemTable memTable = new MemTableImpl(budget);
        EngineRecovery recovery = new EngineRecovery(budget, memTable, mt -> mt.clear(), 64 * 1024 * 1024L);

        System.out.println("Starting recovery benchmark...");
        long start = System.nanoTime();
        recovery.recover(walDir);
        long end = System.nanoTime();

        long durationMs = (end - start) / 1_000_000;
        double recordsPerSecond = recordCount / (durationMs / 1000.0);
        
        System.out.println("Recovery took: " + durationMs + "ms");
        System.out.println("Throughput: " + String.format("%.2f", recordsPerSecond) + " records/sec");
        
        // Extrapolate to 1 billion keys
        double billionKeySeconds = (1_000_000_000L / recordsPerSecond);
        System.out.println("Estimated time for 1 billion keys: " + String.format("%.2f", billionKeySeconds) + "s");

        if (billionKeySeconds < 60) {
            System.out.println("SUCCESS: Meets the < 60s requirement.");
        } else {
            System.out.println("WARNING: May not meet the < 60s requirement on this hardware.");
        }

        // Cleanup
        memTable.close();
        Files.walk(tempDir).map(Path::toFile).forEach(java.io.File::delete);
    }
}
