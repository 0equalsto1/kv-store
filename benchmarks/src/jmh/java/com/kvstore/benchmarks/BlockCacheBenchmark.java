package com.kvstore.benchmarks;

import com.kvstore.cache.BlockCache;
import com.kvstore.cache.LruBlockCache;
import com.kvstore.core.MemoryBudget;
import org.openjdk.jmh.annotations.*;

import java.util.Random;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

@State(Scope.Benchmark)
@BenchmarkMode(Mode.Throughput)
@OutputTimeUnit(TimeUnit.SECONDS)
public class BlockCacheBenchmark {

    private BlockCache cache;
    private byte[] data;
    private final AtomicLong counter = new AtomicLong();

    @Setup(Level.Trial)
    public void setup() {
        MemoryBudget budget = new MemoryBudget(2048L * 1024 * 1024); // 2GB
        cache = new LruBlockCache(1024L * 1024 * 1024, budget); // 1GB cache
        data = new byte[4096]; // 4KB blocks
        new Random().nextBytes(data);
    }

    @TearDown(Level.Trial)
    public void tearDown() throws Exception {
        cache.close();
    }

    @Benchmark
    public void testPut() {
        long c = counter.incrementAndGet();
        cache.put("sstable-1", c, data);
    }

    @Benchmark
    public Object testGet() {
        long c = counter.get();
        long offset = (c > 0) ? (System.nanoTime() % c) : 0;
        return cache.get("sstable-1", offset).orElse(null);
    }
}
