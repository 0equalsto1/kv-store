package com.kvstore.io;

import com.kvstore.core.MemoryBudget;
import com.kvstore.core.StorageErrorCode;
import com.kvstore.core.StorageException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.*;

class MappedFileWriterTest {
    @TempDir
    Path tempDir;

    private MemoryBudget budget;
    private Path testFile;

    @BeforeEach
    void setUp() {
        budget = new MemoryBudget(1024 * 1024); // 1MB budget
        testFile = tempDir.resolve("test-file.db");
    }

    @Test
    void shouldTrackMemoryBudget() throws Exception {
        long size = 4096;
        try (MappedFileWriter writer = new MappedFileWriter(testFile, size, budget)) {
            assertThat(budget.getComponentUsage("io")).isEqualTo(size);
            assertThat(budget.getBytesUsed()).isEqualTo(size);
        }
        assertThat(budget.getComponentUsage("io")).isEqualTo(0);
        assertThat(budget.getBytesUsed()).isEqualTo(0);
    }

    @Test
    void shouldEnforceBudgetLimit() {
        MemoryBudget smallBudget = new MemoryBudget(100);
        assertThatThrownBy(() -> new MappedFileWriter(testFile, 101, smallBudget))
            .isInstanceOf(StorageException.class);
    }

    @Test
    void shouldWriteAndForceData() throws Exception {
        byte[] data = "Hello, FFM!".getBytes();
        try (MappedFileWriter writer = new MappedFileWriter(testFile, 1024, budget)) {
            writer.write(data);
            writer.force();
        }

        // Verify file size and content (could use MappedFileReader later, but let's use simple check for now)
        assertThat(testFile.toFile().length()).isEqualTo(1024);
    }
}
