package com.kvstore.telemetry;

import com.kvstore.core.MemoryBudget;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

/**
 * Micro-benchmark to verify that telemetry overhead is minimal.
 * Target: < 100ns per recording operation.
 */
public class MetricsOverheadTest {

    @Test
    public void testRecordingOverhead() {
        MemoryBudget budget = new MemoryBudget(1024);
        MetricsRecorder recorder = new MetricsRecorder(budget);
        recorder.enable();
        
        int iterations = 1_000_000;
        
        // Warmup
        for (int i = 0; i < 100_000; i++) {
            recorder.recordRead(1);
        }
        
        long start = System.nanoTime();
        for (int i = 0; i < iterations; i++) {
            recorder.recordRead(1);
        }
        long duration = System.nanoTime() - start;
        
        double avgNs = (double) duration / iterations;
        System.out.println("Average recordRead overhead: " + avgNs + " ns");
        
        // AC 3: < 1% overhead. For 100K ops/sec, 1% is 100ns.
        // On modern hardware, this should be well under 50ns.
        assertThat(avgNs < 100).as("Recording overhead should be < 100ns, but was " + avgNs + "ns").isTrue();
    }
}
