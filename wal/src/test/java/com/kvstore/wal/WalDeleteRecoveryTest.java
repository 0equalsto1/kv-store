package com.kvstore.wal;

import com.kvstore.core.MemoryBudget;
import com.kvstore.memtable.MemTable;
import com.kvstore.memtable.MemTableImpl;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Optional;

import static org.assertj.core.api.Assertions.*;

class WalDeleteRecoveryTest {
    @TempDir
    Path tempDir;

    private MemoryBudget budget;
    private Path walPath;

    @BeforeEach
    void setUp() {
        budget = new MemoryBudget(100 * 1024 * 1024);
        walPath = tempDir.resolve("delete.wal");
    }

    @Test
    void shouldRecoverDeletions() throws IOException {
        byte[] key = "key1".getBytes();
        
        try (WalWriter writer = new WalWriter(walPath, 1024 * 1024, budget)) {
            writer.append(new WalRecord(WalRecord.VERSION_1, WalRecord.TYPE_PUT, key, "val1".getBytes(), 0));
            writer.append(new WalRecord(WalRecord.VERSION_1, WalRecord.TYPE_DELETE, key, new byte[0], 0));
            writer.force();
        }

        MemTable memTable = new MemTableImpl(budget);
        try (WalReader reader = new WalReader(walPath, budget)) {
            for (WalRecord record : reader) {
                if (record.type() == WalRecord.TYPE_PUT) {
                    memTable.put(record.key(), record.value());
                } else if (record.type() == WalRecord.TYPE_DELETE) {
                    memTable.delete(record.key());
                }
            }
        }

        assertThat(memTable.get(key).isPresent()).as("Key should be deleted after recovery").isFalse();
    }
}
