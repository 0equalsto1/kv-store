package com.kvstore.core;

import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

public class EngineConfigTest {

    @Test
    public void testDefaultConfig() {
        EngineConfig config = new EngineConfig.Builder().build();
        assertThat(config.maxMemoryBytes()).isEqualTo(100 * 1024 * 1024 * 1024L);
        assertThat(config.memtableMaxSizeBytes()).isEqualTo(64 * 1024 * 1024L);
    }

    @Test
    public void testInvalidConfig() {
        EngineConfig.Builder builder = new EngineConfig.Builder()
            .maxMemoryBytes(100)
            .memtableMaxSizeBytes(60)
            .blockCacheMaxSizeBytes(50);
        
        StorageException exception = catchThrowableOfType(builder::build, StorageException.class);
        assertThat(exception).isNotNull();
        assertThat(exception.getErrorCode()).isEqualTo(StorageErrorCode.INVALID_CONFIGURATION);
    }

    @Test
    public void testCustomConfig() {
        EngineConfig config = new EngineConfig.Builder()
            .maxMemoryBytes(1024)
            .memtableMaxSizeBytes(512)
            .blockCacheMaxSizeBytes(256)
            .build();
            
        assertThat(config.maxMemoryBytes()).isEqualTo(1024);
        assertThat(config.memtableMaxSizeBytes()).isEqualTo(512);
    }
}
