package com.kvstore.indexing;

import com.kvstore.core.MemoryBudget;
import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.Random;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class BloomFilterFalsePositiveTest {
    @Test
    void verifyFalsePositiveProbability() {
        MemoryBudget budget = new MemoryBudget(100 * 1024 * 1024);
        int expectedInsertions = 10000;
        double targetFpp = 0.01;
        
        try (BloomFilter filter = new BloomFilter(expectedInsertions, targetFpp, budget)) {
            Set<String> inserted = new HashSet<>();
            Random random = new Random(42);

            for (int i = 0; i < expectedInsertions; i++) {
                String key = "key-" + i;
                filter.add(key.getBytes());
                inserted.add(key);
            }

            int falsePositives = 0;
            int totalChecks = 100000;
            for (int i = 0; i < totalChecks; i++) {
                String key = "check-" + i;
                if (!inserted.contains(key)) {
                    if (filter.mightContain(key.getBytes())) {
                        falsePositives++;
                    }
                }
            }

            double actualFpp = (double) falsePositives / totalChecks;
            System.out.println("Actual FPP: " + actualFpp + " (Target: " + targetFpp + ")");
            
            // Allow some wiggle room (e.g. 2x target) for probabilistic variance
            assertThat(actualFpp < targetFpp * 2).as("FPP exceeded threshold: " + actualFpp).isTrue();
        }
    }
}
