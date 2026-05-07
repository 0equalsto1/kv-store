package com.kvstore.benchmarks;

import com.kvstore.core.EngineConfig;
import com.kvstore.core.MemoryBudget;
import com.kvstore.engine.StorageEngine;
import org.openjdk.jmh.annotations.*;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Random;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

@State(Scope.Benchmark)
@BenchmarkMode(Mode.Throughput)
@OutputTimeUnit(TimeUnit.SECONDS)
public class StorageEngineBenchmark {

    private StorageEngine engine;
    private Path tempDir;
    private byte[] value;
    private final AtomicLong counter = new AtomicLong();

    @Setup(Level.Trial)
    public void setup() throws IOException {
        tempDir = Files.createTempDirectory("kvstore-benchmark-");
        EngineConfig config = new EngineConfig.Builder()
                .maxMemoryBytes(4096L * 1024 * 1024) // 4GB
                .memtableMaxSizeBytes(64L * 1024 * 1024) // 64MB
                .blockCacheMaxSizeBytes(1024L * 1024 * 1024) // 1GB
                .build();
        engine = new StorageEngine(config, tempDir);
        engine.start();
        
        value = new byte[100];
        new Random().nextBytes(value);
        
        // Pre-fill some data (less than before to avoid initial compaction storm)
        for (int i = 0; i < 10_000; i++) {
            try {
                engine.put(longToBytes(i), value);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }

    private byte[] longToBytes(long l) {
        byte[] result = new byte[8];
        for (int i = 7; i >= 0; i--) {
            result[i] = (byte) (l & 0xFF);
            l >>= 8;
        }
        return result;
    }

    @TearDown(Level.Trial)
    public void tearDown() throws Exception {
        engine.close();
        // Clean up tempDir
        try (var stream = Files.walk(tempDir)) {
            stream.sorted((a, b) -> b.compareTo(a))
                    .forEach(p -> {
                        try {
                            Files.delete(p);
                        } catch (IOException e) {
                            // Ignore
                        }
                    });
        }
    }

    @Benchmark
    public void testPut() throws InterruptedException {
        engine.put(longToBytes(counter.incrementAndGet()), value);
    }

    @Benchmark
    public byte[] testGet() throws InterruptedException {
        // Randomly get one of the pre-filled keys or a new one
        long c = counter.get();
        long keyNum = (c > 0) ? (System.nanoTime() % c) : 0;
        return engine.get(longToBytes(keyNum)).orElse(null);
    }
}
