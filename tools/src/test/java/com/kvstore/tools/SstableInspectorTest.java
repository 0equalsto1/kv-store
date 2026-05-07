package com.kvstore.tools;

import com.kvstore.core.MemoryBudget;
import com.kvstore.sstable.SstableHeader;
import com.kvstore.sstable.SstableIterator;
import com.kvstore.sstable.SstableWriter;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

public class SstableInspectorTest {

    @Test
    public void testMainInitialization() {
        PrintStream originalOut = System.out;
        ByteArrayOutputStream outContent = new ByteArrayOutputStream();
        System.setOut(new PrintStream(outContent));

        try {
            SstableInspector.main(new String[]{});
            assertThat(outContent.toString().contains("SSTable Inspector")).isTrue();
        } finally {
            System.setOut(originalOut);
        }
    }

    @Test
    public void testIntegrityCheck(@TempDir Path tempDir) throws Exception {
        Path sstablePath = tempDir.resolve("test-integrity.sst");
        MemoryBudget budget = new MemoryBudget(1024 * 1024);
        try (SstableWriter writer = new SstableWriter(budget)) {
            List<SstableIterator.Record> records = List.of(
                new SstableIterator.Record((byte)1, "key1".getBytes(), "value1".getBytes()),
                new SstableIterator.Record((byte)1, "key2".getBytes(), "value2".getBytes())
            );
            writer.writeRecords(records.iterator(), 2, sstablePath);
        }

        PrintStream originalOut = System.out;
        ByteArrayOutputStream outContent = new ByteArrayOutputStream();
        System.setOut(new PrintStream(outContent));

        try {
            SstableInspector.main(new String[]{"--verify", sstablePath.toString()});
            String output = outContent.toString();
            assertThat(output.contains("Integrity Check: PASSED")).isTrue();
            assertThat(output.contains("Records scanned: 2")).isTrue();
        } finally {
            System.setOut(originalOut);
        }
    }

    @Test
    public void testDataListing(@TempDir Path tempDir) throws Exception {
        Path sstablePath = tempDir.resolve("test-data.sst");
        MemoryBudget budget = new MemoryBudget(1024 * 1024);
        try (SstableWriter writer = new SstableWriter(budget)) {
            List<SstableIterator.Record> records = List.of(
                new SstableIterator.Record((byte)1, "key1".getBytes(), "value1".getBytes()),
                new SstableIterator.Record((byte)1, "key2".getBytes(), "value2".getBytes()),
                new SstableIterator.Record((byte)1, "key3".getBytes(), "value3".getBytes())
            );
            writer.writeRecords(records.iterator(), 3, sstablePath);
        }

        PrintStream originalOut = System.out;
        ByteArrayOutputStream outContent = new ByteArrayOutputStream();
        System.setOut(new PrintStream(outContent));

        try {
            SstableInspector.main(new String[]{"--list", sstablePath.toString()});
            String output = outContent.toString();
            assertThat(output.contains("key1 => value1")).isTrue();
            assertThat(output.contains("key2 => value2")).isTrue();
            assertThat(output.contains("key3 => value3")).isTrue();
        } finally {
            System.setOut(originalOut);
        }
    }

    @Test
    public void testDataListingWithFilters(@TempDir Path tempDir) throws Exception {
        Path sstablePath = tempDir.resolve("test-filters.sst");
        MemoryBudget budget = new MemoryBudget(1024 * 1024);
        try (SstableWriter writer = new SstableWriter(budget)) {
            List<SstableIterator.Record> records = List.of(
                new SstableIterator.Record((byte)1, "key1".getBytes(), "value1".getBytes()),
                new SstableIterator.Record((byte)1, "key2".getBytes(), "value2".getBytes()),
                new SstableIterator.Record((byte)1, "key3".getBytes(), "value3".getBytes())
            );
            writer.writeRecords(records.iterator(), 3, sstablePath);
        }

        PrintStream originalOut = System.out;
        ByteArrayOutputStream outContent = new ByteArrayOutputStream();
        System.setOut(new PrintStream(outContent));

        try {
            // Using dynamic header size from constant to avoid fragility
            String offset = String.valueOf(SstableHeader.HEADER_SIZE);
            SstableInspector.main(new String[]{"--list", "--offset", offset, "--limit", "100", sstablePath.toString()});
            String output = outContent.toString();
            assertThat(output.contains("key1")).isTrue();
            assertThat(output.contains("key2")).isTrue();
            assertThat(output.contains("key3")).isTrue();
            
            outContent.reset();
            SstableInspector.main(new String[]{"--list", "--start-key", "key2", sstablePath.toString()});
            output = outContent.toString();
            assertThat(output.contains("key1 =>")).isFalse();
            assertThat(output.contains("key2 =>")).isTrue();
            assertThat(output.contains("key3 =>")).isTrue();
        } finally {
            System.setOut(originalOut);
        }
    }
}
