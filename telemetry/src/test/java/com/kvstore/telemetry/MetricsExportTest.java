package com.kvstore.telemetry;

import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

public class MetricsExportTest {

    @Test
    public void testFormatter() {
        MetricsSnapshot snapshot = new MetricsSnapshot(
            100, 50, 1, 5, 10, 2, 8, 15, 0.95, 200, 1024, 2048, 4096, 1, 0
        );
        MetricsFormatter formatter = new MetricsFormatter();
        String output = formatter.format(snapshot);
        
        assertThat(output.contains("Throughput: Read: 100 ops/sec")).isTrue();
        assertThat(output.contains("Cache Hit Ratio: 95.00%")).isTrue();
        assertThat(output.contains("Memory: Heap: 1.00 KB, Off-Heap: 2.00 KB")).isTrue();
    }

    @Test
    public void testPrometheusExporter() {
        MetricsSnapshot snapshot = new MetricsSnapshot(
            100, 50, 1, 5, 10, 2, 8, 15, 0.95, 200, 1024, 2048, 4096, 1, 0
        );
        PrometheusExporter exporter = new PrometheusExporter();
        String output = exporter.export(snapshot);
        
        assertThat(output.contains("kvstore_read_throughput_ops_per_sec 100")).isTrue();
        assertThat(output.contains("# TYPE kvstore_cache_hit_ratio gauge")).isTrue();
        assertThat(output.contains("kvstore_cache_hit_ratio 0.95")).isTrue();
        assertThat(output.contains("kvstore_checksum_errors_total 1")).isTrue();
    }
}
