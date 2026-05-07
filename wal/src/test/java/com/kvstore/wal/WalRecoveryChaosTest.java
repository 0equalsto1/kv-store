package com.kvstore.wal;

import com.kvstore.core.MemoryBudget;
import com.kvstore.core.StorageErrorCode;
import com.kvstore.core.StorageException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.*;

class WalRecoveryChaosTest {
    @TempDir
    Path tempDir;

    private MemoryBudget budget;
    private Path walPath;

    @BeforeEach
    void setUp() {
        budget = new MemoryBudget(100 * 1024 * 1024);
        walPath = tempDir.resolve("chaos.wal");
    }

    @Test
    void shouldHandleTruncatedRecordAtEnd() throws Exception {
        WalRecord r1 = new WalRecord(WalRecord.VERSION_1, WalRecord.TYPE_PUT, "key1".getBytes(), "val1".getBytes(), 0);     
        WalRecord r2 = new WalRecord(WalRecord.VERSION_1, WalRecord.TYPE_PUT, "key2".getBytes(), "val2".getBytes(), 0);     

        long posAfterFirst;

        try (WalWriter writer = new WalWriter(walPath, 1024, budget)) {
            writer.append(r1);
            posAfterFirst = writer.getPosition();
            writer.append(r2);
            writer.force();
        }

        // Manually truncate the file to just 5 bytes into the second record
        try (RandomAccessFile raf = new RandomAccessFile(walPath.toFile(), "rw")) {
            raf.setLength(posAfterFirst + 5);
        }

        List<WalRecord> recovered = new ArrayList<>();
        try (WalReader reader = new WalReader(walPath, budget)) {
            for (WalRecord r : reader) {
                recovered.add(r);
            }
        }

        // Should recover only the first record
        assertThat(recovered.size()).isEqualTo(1);
        assertThat(recovered.get(0).key()).isEqualTo("key1".getBytes());
    }

    @Test
    void shouldStopAtFirstCorruptionDuringRecovery() throws Exception {
        WalRecord r1 = new WalRecord(WalRecord.VERSION_1, WalRecord.TYPE_PUT, "key1".getBytes(), "val1".getBytes(), 0);     
        WalRecord r2 = new WalRecord(WalRecord.VERSION_1, WalRecord.TYPE_PUT, "key2".getBytes(), "val2".getBytes(), 0);     

        try (WalWriter writer = new WalWriter(walPath, 1024, budget)) {
            writer.append(r1);
            writer.append(r2);
            writer.force();
        }

        // Corrupt a byte in the first record (offset 12 is past header)
        try (RandomAccessFile raf = new RandomAccessFile(walPath.toFile(), "rw")) {
            raf.seek(12);
            byte b = raf.readByte();
            raf.seek(12);
            raf.writeByte(b + 1);
        }

        List<WalRecord> recovered = new ArrayList<>();
        try (WalReader reader = new WalReader(walPath, budget)) {
            for (WalRecord r : reader) {
                recovered.add(r);
            }
        }

        // Should recover zero records because the first one is corrupt
        assertThat(recovered.size()).isEqualTo(0);
    }
}
