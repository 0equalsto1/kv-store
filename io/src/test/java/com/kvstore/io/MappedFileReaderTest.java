package com.kvstore.io;

import com.kvstore.core.MemoryBudget;
import com.kvstore.core.StorageException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.*;

class MappedFileReaderTest {
    @TempDir
    Path tempDir;

    private MemoryBudget budget;
    private Path testFile;

    @BeforeEach
    void setUp() throws IOException {
        budget = new MemoryBudget(1024 * 1024);
        testFile = tempDir.resolve("read-test.db");
        byte[] data = new byte[1024];
        for (int i = 0; i < data.length; i++) {
            data[i] = (byte) (i % 128);
        }
        Files.write(testFile, data);
    }

    @Test
    void shouldReadData() throws Exception {
        try (MappedFileReader reader = new MappedFileReader(testFile, budget)) {
            byte[] buffer = new byte[10];
            reader.read(0, buffer);
            for (int i = 0; i < 10; i++) {
                assertThat(buffer[i]).isEqualTo((byte) (i % 128));
            }
        }
    }

    @Test
    void shouldTrackBudget() throws Exception {
        long fileSize = Files.size(testFile);
        try (MappedFileReader reader = new MappedFileReader(testFile, budget)) {
            assertThat(budget.getComponentUsage("io")).isEqualTo(fileSize);
        }
        assertThat(budget.getComponentUsage("io")).isEqualTo(0);
    }

    @Test
    void shouldHandleConcurrentReads() throws Exception {
        try (MappedFileReader reader = new MappedFileReader(testFile, budget)) {
            Thread t1 = new Thread(() -> {
                byte[] b = new byte[100];
                reader.read(0, b);
            });
            Thread t2 = new Thread(() -> {
                byte[] b = new byte[100];
                reader.read(500, b);
            });
            t1.start();
            t2.start();
            t1.join();
            t2.join();
        }
    }
}
