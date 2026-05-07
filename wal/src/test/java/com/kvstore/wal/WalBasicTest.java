package com.kvstore.wal;

import com.kvstore.core.MemoryBudget;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.*;

class WalBasicTest {
    @TempDir
    Path tempDir;

    private MemoryBudget budget;
    private Path walPath;

    @BeforeEach
    void setUp() {
        budget = new MemoryBudget(100 * 1024 * 1024); // 100MB
        walPath = tempDir.resolve("test.wal");
    }

    @Test
    void shouldWriteAndReadRecords() throws Exception {
        List<WalRecord> records = new ArrayList<>();
        records.add(new WalRecord(WalRecord.VERSION_1, WalRecord.TYPE_PUT, "k1".getBytes(), "v1".getBytes(), 0));
        records.add(new WalRecord(WalRecord.VERSION_1, WalRecord.TYPE_DELETE, "k2".getBytes(), new byte[0], 0));
        records.add(new WalRecord(WalRecord.VERSION_1, WalRecord.TYPE_PUT, "k3".getBytes(), "v3-much-longer-value".getBytes(), 0));

        try (WalWriter writer = new WalWriter(walPath, 1024 * 1024, budget)) {
            for (WalRecord r : records) {
                writer.append(r);
            }
            writer.force();
        }

        List<WalRecord> readRecords = new ArrayList<>();
        try (WalReader reader = new WalReader(walPath, budget)) {
            for (WalRecord r : reader) {
                readRecords.add(r);
            }
        }

        assertThat(readRecords.size()).isEqualTo(records.size());
        for (int i = 0; i < records.size(); i++) {
            WalRecord expected = records.get(i);
            WalRecord actual = readRecords.get(i);
            assertThat(actual.version()).isEqualTo(expected.version());
            assertThat(actual.type()).isEqualTo(expected.type());
            assertThat(actual.key()).isEqualTo(expected.key());
            assertThat(actual.value()).isEqualTo(expected.value());
            assertThat(actual.crc32() != 0).isTrue();
        }
    }
}
