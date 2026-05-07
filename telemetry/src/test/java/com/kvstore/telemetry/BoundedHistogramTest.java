package com.kvstore.telemetry;

import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

import java.util.Arrays;

public class BoundedHistogramTest {

    @Test
    public void testPercentiles() {
        BoundedHistogram histogram = new BoundedHistogram(100);
        for (int i = 1; i <= 100; i++) {
            histogram.record(i);
        }
        
        assertThat(histogram.getPercentile(50)).isEqualTo(50);
        assertThat(histogram.getPercentile(90)).isEqualTo(90);
        assertThat(histogram.getPercentile(99)).isEqualTo(99);
        assertThat(histogram.getPercentile(100)).isEqualTo(100);
    }

    @Test
    public void testWrapping() {
        BoundedHistogram histogram = new BoundedHistogram(10);
        for (int i = 1; i <= 20; i++) {
            histogram.record(i);
        }
        
        // Should contain 11-20
        assertThat(histogram.getPercentile(50)).isEqualTo(15);
        assertThat(histogram.getPercentile(90)).isEqualTo(19);
        assertThat(histogram.getPercentile(100)).isEqualTo(20);
    }
}
