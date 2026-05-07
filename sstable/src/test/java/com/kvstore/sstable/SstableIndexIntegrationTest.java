package com.kvstore.sstable;

import com.kvstore.core.MemoryBudget;
import com.kvstore.memtable.MemTable;
import com.kvstore.memtable.MemTableImpl;
import com.kvstore.indexing.SparseIndex;
import com.kvstore.io.MappedFileReader;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.*;

class SstableIndexIntegrationTest {
    @TempDir
    Path tempDir;

    private MemoryBudget budget;
    private SstableWriter writer;

    @BeforeEach
    void setUp() {
        budget = new MemoryBudget(100 * 1024 * 1024);
        writer = new SstableWriter(budget);
    }

    @Test
    void shouldProduceValidSparseIndex() throws IOException {
        MemTable memTable = new MemTableImpl(budget);
        // Write 300 keys to trigger at least 3 index entries (interval 128)
        for (int i = 0; i < 300; i++) {
            memTable.put(String.format("key-%03d", i).getBytes(), "val".getBytes());
        }

        Path sstablePath = tempDir.resolve("indexed.sst");
        writer.flush(memTable, sstablePath);

        try (MappedFileReader reader = new MappedFileReader(sstablePath, budget, "sstable")) {
            SstableHeader header = SstableHeaderCodec.decode(reader.getSegment(), 0);
            
            assertThat(header.indexOffset() > SstableHeader.HEADER_SIZE).isTrue();
            assertThat(header.indexOffset() < sstablePath.toFile().length()).isTrue();

            // Index section is from indexOffset to end of file
            long indexSize = sstablePath.toFile().length() - header.indexOffset();
            SparseIndex index = new SparseIndex(reader.getSegment().asSlice(header.indexOffset(), indexSize));
            
            // "key-000" should be at index entry 0
            long offset0 = index.findFloorOffset("key-000".getBytes());
            assertThat(offset0).isEqualTo(header.dataOffset());

            // "key-128" should be at index entry 1
            long offset128 = index.findFloorOffset("key-128".getBytes());
            assertThat(offset128 > offset0).isTrue();

            // "key-256" should be at index entry 2
            long offset256 = index.findFloorOffset("key-256".getBytes());
            assertThat(offset256 > offset128).isTrue();

            // "key-299" should floor to offset256 (the last entry)
            assertThat(index.findFloorOffset("key-299".getBytes())).isEqualTo(offset256);
        }
    }
}
