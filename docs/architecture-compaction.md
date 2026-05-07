# Architecture - Compaction Module

The `compaction` module is responsible for the background maintenance and space reclamation of the LSM-tree.

## Key Components

### `CompactionEngine`
The core logic for merging multiple SSTables.
- **Merge-Sort:** Uses a `MergingIterator` to combine sorted records from multiple sources.
- **Tombstone Removal:** During "major" compactions, records marked as deleted are permanently removed.
- **Atomic Swap:** Writes the merged data to a new SSTable and replaces the old ones in the engine's list.

### `CompactionScheduler`
Manages the lifecycle and frequency of compaction tasks.
- **Prioritization:** Identifies which SSTables are the best candidates for merging based on size and count.
- **Concurrency:** Executes compaction tasks in a background thread pool (e.g., `ForkJoinPool`) to avoid blocking user operations.

### `MergingIterator`
A specialized iterator that performs a multi-way merge-sort across multiple `SstableIterator` instances.

## Write Backpressure

Compaction lag is a critical metric for system health. If the rate of incoming writes exceeds the engine's ability to compact files, the `CompactionScheduler` signals the `concurrency` module to slow down or block new writes until the lag is reduced.
