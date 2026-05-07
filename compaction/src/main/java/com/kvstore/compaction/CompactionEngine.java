package com.kvstore.compaction;

import com.kvstore.core.MemoryBudget;
import com.kvstore.sstable.SstableIterator;
import com.kvstore.sstable.SstableReader;
import com.kvstore.sstable.SstableWriter;

import java.nio.file.Path;
import java.util.Iterator;
import java.util.List;

/**
 * Core engine responsible for executing compaction operations in the LSM-tree.
 * <p>
 * Compaction is the process of merging multiple SSTables into a single, larger SSTable.
 * This process serves several critical architectural purposes:
 * <ul>
 *     <li><b>Space Reclamation:</b> Removes obsolete versions of keys (shadowed by newer writes) 
 *         and physically deletes records marked with tombstones.</li>
 *     <li><b>Read Optimization:</b> Reduces "read amplification" by decreasing the total 
 *         number of SSTables that must be consulted during a point lookup or range scan.</li>
 *     <li><b>Data Locality:</b> Re-organizes data into a single contiguous sorted stream 
 *         for more efficient sequential I/O.</li>
 * </ul>
 * <p>
 * The engine uses a streaming merge approach to keep memory usage constant regardless 
 * of the size of the SSTables being merged.
 */
public class CompactionEngine {
    /** Global memory budget used to track memory allocated during the compaction process. */
    private final MemoryBudget budget;

    /**
     * Constructs a {@code CompactionEngine} with a specified memory budget.
     *
     * @param budget the {@link MemoryBudget} used to track and limit memory usage 
     *               during compaction (e.g., for buffering).
     */
    public CompactionEngine(MemoryBudget budget) {
        this.budget = budget;
    }

    /**
     * Executes a compaction operation on a set of input SSTables.
     * <p>
     * Implementation Details:
     * <ol>
     *     <li>Opens iterators for all input SSTables.</li>
     *     <li>Uses a {@link MergingIterator} to provide a unified sorted view of all records.</li>
     *     <li>Wraps the merger in a {@link FilteringIterator} to optionally remove tombstones.</li>
     *     <li>Writes the resulting stream to a new SSTable file using {@link SstableWriter}.</li>
     *     <li>Handles cleanup of iterators even in the event of failures.</li>
     * </ol>
     *
     * @param inputs      the list of {@link SstableReader}s for the SSTables to be merged.
     * @param outputDir   the directory where the new compacted SSTable will be written.
     * @param newFileName the name for the newly created SSTable file.
     * @param isMajor     whether this is a "major" compaction. Major compactions merge 
     *                    <i>all</i> existing SSTables, meaning tombstones can be safely 
     *                    discarded as no older versions of the keys can exist in other files.
     * @return the {@link Path} to the newly created and compacted SSTable file.
     * @throws RuntimeException if the compaction process fails due to I/O errors, 
     *                          checksum mismatches, or resource exhaustion.
     */
    public Path compact(List<SstableReader> inputs, Path outputDir, String newFileName, boolean isMajor) {
        Path outputPath = outputDir.resolve(newFileName);
        List<SstableIterator> iterators = new java.util.ArrayList<>();
        try {
            for (SstableReader reader : inputs) {
                iterators.add(reader.iterator());
            }
        
            try (MergingIterator mergingIt = new MergingIterator(iterators);
                 SstableWriter writer = new SstableWriter(budget, "compaction")) {
                
                // Wrap merging iterator to filter tombstones if major compaction.
                // Logic: In a major compaction, we know there are no older versions 
                // of keys in any other SSTables, so tombstones are no longer needed 
                // to "hide" old data.
                Iterator<SstableIterator.Record> filtered = new FilteringIterator(mergingIt, isMajor);
                
                // SstableWriter handles atomic rename and metadata generation internally.
                writer.writeRecords(filtered, -1, outputPath);
                
                return outputPath;
            }
        } catch (Exception e) {
            // Close iterators if MergingIterator or SstableWriter fails to initialize or execute.
            // Critical for preventing memory-mapped file leaks.
            for (SstableIterator it : iterators) {
                try { it.close(); } catch (Exception ignored) {}
            }
            if (e instanceof RuntimeException) throw (RuntimeException) e;
            throw new RuntimeException("Compaction failed: " + e.getMessage(), e);
        }
    }

    /**
     * Internal iterator that filters records from a source iterator.
     * Primarily used for tombstone removal during major compactions.
     */
    private static class FilteringIterator implements Iterator<SstableIterator.Record> {
        /** The source stream of merged records. */
        private final MergingIterator source;
        /** Whether to physically remove records marked as tombstones. */
        private final boolean removeTombstones;
        /** The next record to be returned by {@link #next()}. */
        private SstableIterator.Record next;

        /**
         * Constructs a {@code FilteringIterator}.
         *
         * @param source           the source of merged records.
         * @param removeTombstones whether to filter out tombstones.
         */
        public FilteringIterator(MergingIterator source, boolean removeTombstones) {
            this.source = source;
            this.removeTombstones = removeTombstones;
            advance();
        }

        /**
         * Advances the source iterator until a valid (non-filtered) record is found.
         */
        private void advance() {
            next = null;
            while (source.hasNext()) {
                SstableIterator.Record r = source.next();
                // Filter logic: if it's a major compaction and the record is a tombstone (type 2), skip it.
                if (removeTombstones && r.type() == 2) {
                    continue;
                }
                next = r;
                break;
            }
        }

        /**
         * Checks if there are more valid records.
         * @return {@code true} if a record is available.
         */
        @Override
        public boolean hasNext() {
            return next != null;
        }

        /**
         * Returns the next valid record and advances the source.
         * @return the next record.
         * @throws java.util.NoSuchElementException if no more records are available.
         */
        @Override
        public SstableIterator.Record next() {
            if (!hasNext()) throw new java.util.NoSuchElementException();
            SstableIterator.Record ret = next;
            advance();
            return ret;
        }
    }
}
