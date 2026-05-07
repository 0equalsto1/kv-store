package com.kvstore.benchmarks;

import com.kvstore.core.MemoryBudget;
import com.kvstore.memtable.MemTable;
import com.kvstore.memtable.MemTableImpl;
import org.openjdk.jmh.annotations.*;

import java.util.Random;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

@State(Scope.Benchmark)
@BenchmarkMode(Mode.Throughput)
@OutputTimeUnit(TimeUnit.SECONDS)
public class MemTableBenchmark {

    private MemTable memTable;
    private byte[] value;
    private final AtomicLong counter = new AtomicLong();

    @Setup(Level.Trial)
    public void setup() {
        MemoryBudget budget = new MemoryBudget(1024L * 1024 * 1024); // 1GB
        memTable = new MemTableImpl(budget);
        value = new byte[100];
        new Random().nextBytes(value);
    }

    private byte[] longToBytes(long l) {
        byte[] result = new byte[8];
        for (int i = 7; i >= 0; i--) {
            result[i] = (byte) (l & 0xFF);
            l >>= 8;
        }
        return result;
    }

    @Benchmark
    public void testPut() {
        memTable.put(longToBytes(counter.incrementAndGet()), value);
    }

    @Benchmark
    public byte[] testGet() {
        long c = counter.get();
        long keyNum = (c > 0) ? (System.nanoTime() % c) : 0;
        return memTable.get(longToBytes(keyNum)).orElse(null);
    }
}
