package com.kvstore.concurrency;

import org.junit.jupiter.api.Test;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.*;

class WriteBackpressureTest {

    @Test
    void shouldAllowWriteWhenLagBelowThreshold() {
        WriteBackpressure backpressure = new WriteBackpressure(100);
        assertThat(backpressure.canWrite()).isTrue();
        
        backpressure.signalCompactionLag(50);
        assertThat(backpressure.canWrite()).isTrue();
    }

    @Test
    void shouldDenyWriteWhenLagAboveThreshold() {
        WriteBackpressure backpressure = new WriteBackpressure(100);
        backpressure.signalCompactionLag(150);
        assertThat(backpressure.canWrite()).isFalse();
    }

    @Test
    void shouldBlockUntilCapacityAvailable() throws InterruptedException {
        WriteBackpressure backpressure = new WriteBackpressure(100);
        backpressure.signalCompactionLag(150);

        CountDownLatch latch = new CountDownLatch(1);
        AtomicBoolean completed = new AtomicBoolean(false);

        Thread virtualThread = Thread.ofVirtual().start(() -> {
            try {
                backpressure.waitForCapacity();
                completed.set(true);
                latch.countDown();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });

        // Should be blocked
        assertThat(latch.await(100, TimeUnit.MILLISECONDS)).isFalse();
        assertThat(completed.get()).isFalse();

        // Clear backpressure
        backpressure.signalCompactionComplete();

        // Should now complete
        assertThat(latch.await(1, TimeUnit.SECONDS)).isTrue();
        assertThat(completed.get()).isTrue();
    }
}
