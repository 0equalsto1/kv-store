package com.kvstore.telemetry;

import com.kvstore.core.MemoryBudget;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

public class MetricsRecorderTest {

    private MemoryBudget budget;
    private MetricsRecorder recorder;

    @BeforeEach
    public void setup() {
        budget = new MemoryBudget(1024 * 1024);
        recorder = new MetricsRecorder(budget);
    }

    @Test
    public void testThroughput() {
        recorder.enable();
        
        recorder.recordRead(10);
        recorder.recordWrite(20);
        
        // At start, elapsedNano is very small, so calculateThroughput returns totalOps
        assertThat(recorder.getReadThroughputOpsPerSec()).isEqualTo(1);
        assertThat(recorder.getWriteThroughputOpsPerSec()).isEqualTo(1);
    }

    @Test
    public void testEnabledToggle() {
        recorder.disable();
        
        recorder.recordRead(10);
        assertThat(recorder.getReadThroughputOpsPerSec()).isEqualTo(0);
        
        recorder.enable();
        recorder.recordRead(10);
        assertThat(recorder.getReadThroughputOpsPerSec() > 0).isTrue();
    }

    @Test
    public void testMemoryIntegration() {
        recorder.enable();
        budget.registerComponent("cache");
        budget.allocate("cache", 512);
        
        assertThat(recorder.getTotalMemoryLimitBytes()).isEqualTo(1024 * 1024);
        assertThat(recorder.getMemoryUsageBytes("cache")).isEqualTo(512);
    }

    @Test
    public void testSnapshot() {
        recorder.enable();
        recorder.recordRead(10);
        recorder.setCompactionLagMs(100);
        
        MetricsSnapshot snapshot = recorder.takeSnapshot();
        
        assertThat(snapshot.readThroughputOpsPerSec()).isEqualTo(1);
        assertThat(snapshot.compactionLagMs()).isEqualTo(100);
    }

    @Test
    public void testSamplingAndPercentiles() {
        recorder.enable();
        
        // Record 200 reads with varying latencies
        // Sample rate is 100, so 2 samples will be recorded
        for (int i = 1; i <= 200; i++) {
            recorder.recordRead(i);
        }
        
        // Percentiles should be based on sampled values (100 and 200)
        assertThat(recorder.getReadLatencyP50Ms()).isEqualTo(100);
        assertThat(recorder.getReadLatencyP999Ms()).isEqualTo(200);
    }

    @Test
    public void testCacheRatio() {
        recorder.enable();
        
        recorder.recordCacheHit();
        recorder.recordCacheHit();
        recorder.recordCacheMiss();
        
        assertThat(recorder.getCacheHitRatio()).isCloseTo(2.0/3.0, offset(0.01));
    }

    @Test
    public void testErrorCounters() {
        recorder.enable();
        
        recorder.recordChecksumError();
        recorder.recordBudgetExceeded();
        
        assertThat(recorder.getChecksumErrorCount()).isEqualTo(1);
        assertThat(recorder.getBudgetExceededCount()).isEqualTo(1);
    }
}
