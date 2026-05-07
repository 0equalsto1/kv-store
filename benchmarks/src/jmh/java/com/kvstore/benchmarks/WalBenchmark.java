package com.kvstore.benchmarks;

import com.kvstore.core.MemoryBudget;
import com.kvstore.wal.WalWriter;
import org.openjdk.jmh.annotations.*;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Random;
import java.util.concurrent.TimeUnit;

@State(Scope.Benchmark)
@BenchmarkMode(Mode.Throughput)
@OutputTimeUnit(TimeUnit.SECONDS)
public class WalBenchmark {

    private WalWriter writer;
    private Path tempFile;
    private byte[] key;
    private byte[] value;

    @Setup(Level.Trial)
    public void setup() throws IOException {
        tempFile = Files.createTempFile("wal-benchmark", ".wal");
        MemoryBudget budget = new MemoryBudget(1024L * 1024 * 1024); // 1GB
        writer = new WalWriter(tempFile, 512L * 1024 * 1024, budget); // 512MB WAL
        
        key = new byte[16];
        value = new byte[100];
        Random random = new Random();
        random.nextBytes(key);
        random.nextBytes(value);
    }

    @TearDown(Level.Trial)
    public void tearDown() throws IOException {
        writer.close();
        Files.deleteIfExists(tempFile);
    }

    @Benchmark
    public void testAppendPut() {
        writer.appendPut(key, value);
    }
}
