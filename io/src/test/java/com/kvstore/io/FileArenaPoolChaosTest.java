package com.kvstore.io;

import com.kvstore.core.MemoryBudget;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.*;

class FileArenaPoolChaosTest {
    @TempDir
    Path tempDir;

    @Test
    void shouldHandleConcurrentAccessAndAbruptClose() throws Exception {
        MemoryBudget budget = new MemoryBudget(100 * 1024 * 1024); // 100MB
        FileArenaPool pool = new FileArenaPool(budget);
        
        int numFiles = 10;
        List<Path> files = new ArrayList<>();
        for (int i = 0; i < numFiles; i++) {
            Path p = tempDir.resolve("chaos-" + i + ".db");
            Files.write(p, new byte[1024]);
            files.add(p);
        }

        int numThreads = 20;
        ExecutorService executor = Executors.newFixedThreadPool(numThreads);
        
        for (int i = 0; i < 100; i++) {
            final int fileIdx = i % numFiles;
            executor.submit(() -> {
                try {
                    MappedFileReader reader = pool.getReader(files.get(fileIdx));
                    byte[] buf = new byte[10];
                    reader.read(0, buf);
                } catch (Exception ignored) {
                    // Expecting some exceptions when pool is closed abruptly
                }
            });
        }

        // Wait a bit then close abruptly
        Thread.sleep(50);
        pool.close();
        
        executor.shutdown();
        executor.awaitTermination(1, TimeUnit.SECONDS);

        // Verify budget is fully returned
        assertThat(budget.getBytesUsed()).as("Budget should be 0 after pool close").isEqualTo(0);
    }
}
