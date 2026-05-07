package com.kvstore.telemetry;

/**
 * Control interface for metrics lifecycle.
 * <p>
 * This interface allows enabling, disabling, and checking the status of 
 * the metrics collection system. It is typically used to toggle telemetry
 * gathering based on configuration or administrative requests.
 * </p>
 * <p>
 * When disabled, the metrics collection should have near-zero overhead, 
 * typically achieved by checking a volatile boolean flag before recording
 * any data.
 * </p>
 */
public interface MetricsController {
    /**
     * Enables metrics collection.
     * <p>
     * After this call, subsequent recording operations should capture data.
     * </p>
     */
    void enable();

    /**
     * Disables metrics collection.
     * <p>
     * After this call, recording operations should be effectively no-ops
     * to minimize performance overhead.
     * </p>
     */
    void disable();

    /**
     * Checks if metrics collection is currently enabled.
     *
     * @return {@code true} if enabled, {@code false} otherwise.
     */
    boolean isEnabled();
}
