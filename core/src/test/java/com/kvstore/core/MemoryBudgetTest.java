package com.kvstore.core;

import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

public class MemoryBudgetTest {

    @Test
    public void testAllocationTracking() {
        MemoryBudget budget = new MemoryBudget(1000);
        MemoryTracker tracker = budget.registerComponent("test");
        
        tracker.allocate(100);
        assertThat(budget.getBytesUsed()).isEqualTo(100);
        assertThat(budget.getComponentUsage("test")).isEqualTo(100);
        
        tracker.deallocate(40);
        assertThat(budget.getBytesUsed()).isEqualTo(60);
    }

    @Test
    public void testBudgetExceeded() {
        MemoryBudget budget = new MemoryBudget(100);
        MemoryTracker tracker = budget.registerComponent("test");
        
        tracker.allocate(60);
        
        StorageException exception = catchThrowableOfType(() -> tracker.allocate(50), StorageException.class);
        assertThat(exception).isNotNull();
        assertThat(exception.getErrorCode()).isEqualTo(StorageErrorCode.BUDGET_EXCEEDED);
        
        // Ensure state was rolled back
        assertThat(budget.getBytesUsed()).isEqualTo(60);
    }

    @Test
    public void testUnregisteredComponent() {
        MemoryBudget budget = new MemoryBudget(100);
        assertThatThrownBy(() -> budget.allocate("unknown", 10)).isInstanceOf(StorageException.class);
    }
}
