package com.kvstore.sstable;

import com.kvstore.cache.BlockCache;
import com.kvstore.cache.LruBlockCache;
import com.kvstore.core.MemoryBudget;
import com.kvstore.core.StorageErrorCode;
import com.kvstore.core.StorageException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.RandomAccessFile;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.*;

public class SstableChecksumTest {

    @Test
    public void testValidSstableRead(@TempDir Path tempDir) throws Exception {
        MemoryBudget budget = new MemoryBudget(1024 * 1024);
        BlockCache cache = new LruBlockCache(1024 * 1024, budget);
        Path sstablePath = tempDir.resolve("test.sst");

        try (SstableWriter writer = new SstableWriter(budget)) {
            List<SstableIterator.Record> records = List.of(
                new SstableIterator.Record((byte) 1, "key1".getBytes(), "value1".getBytes()),
                new SstableIterator.Record((byte) 1, "key2".getBytes(), "value2".getBytes())
            );
            writer.writeRecords(records.iterator(), records.size(), sstablePath);
        }

        try (SstableReader reader = new SstableReader(sstablePath, budget, cache)) {
            ReadResult r1 = reader.get("key1".getBytes());
            assertThat(r1.status() == ReadStatus.FOUND).isTrue();
            assertThat(r1.value()).isEqualTo("value1".getBytes());

            ReadResult r2 = reader.get("key2".getBytes());
            assertThat(r2.status() == ReadStatus.FOUND).isTrue();
            assertThat(r2.value()).isEqualTo("value2".getBytes());
        }
    }

    @Test
    public void testCorruptedSstableRead(@TempDir Path tempDir) throws Exception {
        MemoryBudget budget = new MemoryBudget(1024 * 1024);
        BlockCache cache = new LruBlockCache(1024 * 1024, budget);
        Path sstablePath = tempDir.resolve("corrupted.sst");

        try (SstableWriter writer = new SstableWriter(budget)) {
            List<SstableIterator.Record> records = List.of(
                new SstableIterator.Record((byte) 1, "key1".getBytes(), "value1".getBytes())
            );
            writer.writeRecords(records.iterator(), records.size(), sstablePath);
        }

        // Manually corrupt the first byte of the payload (the type byte)
        try (RandomAccessFile raf = new RandomAccessFile(sstablePath.toFile(), "rw")) {
            raf.seek(SstableHeader.HEADER_SIZE);
            byte b = raf.readByte();
            raf.seek(SstableHeader.HEADER_SIZE);
            raf.writeByte(b ^ 0xFF); // Flip all bits
        }

        try (SstableReader reader = new SstableReader(sstablePath, budget, cache)) {
            StorageException ex = catchThrowableOfType(() -> reader.get("key1".getBytes()), StorageException.class);
            assertThat(ex).isNotNull();
            assertThat(ex.getErrorCode()).isEqualTo(StorageErrorCode.CHECKSUM_MISMATCH);
        }
    }
    
    @Test
    public void testCorruptedSstableScan(@TempDir Path tempDir) throws Exception {
        MemoryBudget budget = new MemoryBudget(1024 * 1024);
        Path sstablePath = tempDir.resolve("corrupted_scan.sst");

        try (SstableWriter writer = new SstableWriter(budget)) {
            List<SstableIterator.Record> records = List.of(
                new SstableIterator.Record((byte) 1, "key1".getBytes(), "value1".getBytes())
            );
            writer.writeRecords(records.iterator(), records.size(), sstablePath);
        }

        // Manually corrupt the value
        try (RandomAccessFile raf = new RandomAccessFile(sstablePath.toFile(), "rw")) {
            // Header(40) + Type(1) + KeyLen(4) + ValLen(4) + Key(4) = 53. Value starts at 53.
            raf.seek(53); 
            byte b = raf.readByte();
            raf.seek(53);
            raf.writeByte(b ^ 0xFF);
        }

        BlockCache cache = new LruBlockCache(1024 * 1024, budget);
        try (SstableReader reader = new SstableReader(sstablePath, budget, cache);
             SstableIterator it = reader.iterator()) {
            StorageException ex = catchThrowableOfType(() -> it.next(), StorageException.class);
            assertThat(ex).isNotNull();
            assertThat(ex.getErrorCode()).isEqualTo(StorageErrorCode.CHECKSUM_MISMATCH);
        }
    }
}
