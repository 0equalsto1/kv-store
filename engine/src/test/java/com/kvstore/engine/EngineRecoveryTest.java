package com.kvstore.engine;

import com.kvstore.core.EngineConfig;
import com.kvstore.core.MemoryBudget;
import com.kvstore.core.StorageErrorCode;
import com.kvstore.core.StorageException;
import com.kvstore.memtable.MemTable;
import com.kvstore.memtable.MemTableImpl;
import com.kvstore.wal.WalRecord;
import com.kvstore.wal.WalWriter;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.RandomAccessFile;
import java.nio.file.Path;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.*;

public class EngineRecoveryTest {

    @Test
    public void testRecoveryFromWal(@TempDir Path tempDir) throws Exception {
        Path walDir = tempDir.resolve("wal");
        java.nio.file.Files.createDirectories(walDir);
        
        MemoryBudget budget = new MemoryBudget(10 * 1024 * 1024);
        
        try (WalWriter writer = new WalWriter(walDir.resolve("wal-1.wal"), 1024 * 1024, budget)) {
            writer.appendPut("key1".getBytes(), "value1".getBytes());
            writer.appendPut("key2".getBytes(), "value2".getBytes());
        }
        
        try (WalWriter writer = new WalWriter(walDir.resolve("wal-2.wal"), 1024 * 1024, budget)) {
            writer.appendPut("key3".getBytes(), "value3".getBytes());
            writer.appendDelete("key1".getBytes());
        }

        MemTable memTable = new MemTableImpl(budget);
        EngineRecovery recovery = new EngineRecovery(budget, memTable, null, 1024 * 1024);
        recovery.recover(walDir);

        assertThat(memTable.get("key1".getBytes())).isEqualTo(Optional.empty());
        assertThat(memTable.get("key2".getBytes()).get()).isEqualTo("value2".getBytes());
        assertThat(memTable.get("key3".getBytes()).get()).isEqualTo("value3".getBytes());
    }

    @Test
    public void testRecoveryAfterCrash(@TempDir Path tempDir) throws Exception {
        EngineConfig config = new EngineConfig.Builder()
            .maxMemoryBytes(200 * 1024 * 1024) 
            .memtableMaxSizeBytes(1024 * 1024)
            .blockCacheMaxSizeBytes(1024 * 1024)
            .build();
        
        try (StorageEngine engine = new StorageEngine(config, tempDir)) {
            engine.start();
            engine.put("key-crash".getBytes(), "value-crash".getBytes());
        }
        
        try (StorageEngine engine = new StorageEngine(config, tempDir)) {
            engine.start();
            Optional<byte[]> val = engine.get("key-crash".getBytes());
            assertThat(val.isPresent()).as("Value should be recovered").isTrue();
            assertThat(val.get()).isEqualTo("value-crash".getBytes());
        }
    }

    @Test
    public void testRecoveryWithFlushes(@TempDir Path tempDir) throws Exception {
        Path walDir = tempDir.resolve("wal");
        java.nio.file.Files.createDirectories(walDir);
        
        MemoryBudget budget = new MemoryBudget(100 * 1024); 
        AtomicInteger flushCount = new AtomicInteger(0);
        
        try (WalWriter writer = new WalWriter(walDir.resolve("wal-1.wal"), 50 * 1024, budget)) {
            for (int i = 0; i < 200; i++) {
                writer.appendPut(("key-" + i).getBytes(), new byte[100]); 
            }
        }

        MemTable memTable = new MemTableImpl(budget);
        EngineRecovery recovery = new EngineRecovery(budget, memTable, mt -> {
            flushCount.incrementAndGet();
            mt.clear();
        }, 4 * 1024); 
        
        recovery.recover(walDir);
        
        assertThat(flushCount.get() > 0).as("Should have triggered at least one flush").isTrue();
        // We added 200 records, each ~170 bytes. 200 * 170 = 34000. 
        // 34000 / 4096 = ~8 flushes. 
        // 34000 % 4096 = ~1200 bytes remaining.
        assertThat(memTable.sizeInBytes() > 0).isTrue();
    }

    @Test
    public void testRecoveryWithCorruption(@TempDir Path tempDir) throws Exception {
        Path walDir = tempDir.resolve("wal");
        java.nio.file.Files.createDirectories(walDir);
        MemoryBudget budget = new MemoryBudget(10 * 1024 * 1024);
        Path walPath = walDir.resolve("corrupt.wal");

        long secondRecordPos;
        try (WalWriter writer = new WalWriter(walPath, 1024, budget)) {
            writer.appendPut("key1".getBytes(), "val1".getBytes());
            secondRecordPos = writer.getPosition();
            writer.appendPut("key2".getBytes(), "val2".getBytes());
        }

        // Corrupt the second record's checksum (at the end of the record)
        try (RandomAccessFile raf = new RandomAccessFile(walPath.toFile(), "rw")) {
            // Seek to somewhere in the middle of the second record
            raf.seek(secondRecordPos + 5);
            byte b = raf.readByte();
            raf.seek(secondRecordPos + 5);
            raf.writeByte(b + 1);
        }

        MemTable memTable = new MemTableImpl(budget);
        EngineRecovery recovery = new EngineRecovery(budget, memTable, null, 1024 * 1024);
        recovery.recover(walDir);

        // Should have key1 but not key2
        assertThat(memTable.get("key1".getBytes()).isPresent()).as("key1 should be present").isTrue();
        assertThat(memTable.get("key2".getBytes()).isEmpty()).as("key2 should be absent due to corruption").isTrue();
    }
}
