package com.kvstore.compaction;

import com.kvstore.core.util.ByteArrayComparator;
import com.kvstore.sstable.SstableIterator;
import java.util.Comparator;
import java.util.List;
import java.util.PriorityQueue;

/**
 * An iterator that merges multiple sorted SSTable iterators into a single sorted stream.
 * <p>
 * This iterator implements a multi-way merge using a {@link PriorityQueue}.
 * It ensures that for any given key, only the version from the newest SSTable is returned,
 * effectively performing "shadowing" where newer records replace older ones.
 * <p>
 * Input iterators must be provided in order from NEWEST to OLDEST for shadowing to work correctly.
 * <p>
 * Logic and Implementation:
 * <ul>
 *     <li>Uses a min-heap (PriorityQueue) to keep track of the current record from each SSTable.</li>
 *     <li>The PriorityQueue is ordered primarily by key (lexicographically) and secondarily 
 *         by SSTable age (sequence number).</li>
 *     <li>When {@link #next()} is called, the smallest key from the PriorityQueue is selected.</li>
 *     <li>Crucially, the iterator then "skips" all subsequent records with the same key 
 *         from older SSTables, ensuring only the most recent version is exposed.</li>
 * </ul>
 */
public class MergingIterator implements AutoCloseable {
    /** 
     * PriorityQueue containing the current head of each input iterator.
     * Ordered by key, and then by sequence number to ensure newer versions shadow older ones.
     */
    private final PriorityQueue<PeekingIterator> pq;

    /**
     * Constructs a {@code MergingIterator} from a list of SSTable iterators.
     *
     * @param iterators a list of {@link SstableIterator}s, ordered from NEWEST to OLDEST.
     *                  The index in the list determines the sequence number (lower is newer).
     */
    public MergingIterator(List<SstableIterator> iterators) {
        this.pq = new PriorityQueue<>((a, b) -> {
            int cmp = ByteArrayComparator.INSTANCE.compare(a.peekKey(), b.peekKey());
            if (cmp != 0) return cmp;
            // Stable sort: pick the one with the smallest sequence (newest)
            // Architecture Pattern: Shadowing requires deterministic priority for ties.
            return Integer.compare(a.sequence, b.sequence);
        });
        
        for (int i = 0; i < iterators.size(); i++) {
            SstableIterator it = iterators.get(i);
            if (it.hasNext()) {
                pq.add(new PeekingIterator(it, i));
            }
        }
    }

    /**
     * Checks if there are more records available across all underlying SSTables.
     *
     * @return {@code true} if more records are available; {@code false} otherwise.
     */
    public boolean hasNext() {
        return !pq.isEmpty();
    }

    /**
     * Returns the next record in sorted order, ensuring shadowed records are skipped.
     * <p>
     * Implementation Detail: This method polls the PriorityQueue for the "best" candidate.
     * It then proactively drains the heads of all other iterators that have the same key,
     * as those records are considered obsolete (shadowed).
     *
     * @return the next {@link SstableIterator.Record} representing the freshest version of the key.
     * @throws java.util.NoSuchElementException if no more records are available.
     */
    public SstableIterator.Record next() {
        if (!hasNext()) {
            throw new java.util.NoSuchElementException();
        }

        PeekingIterator newest = pq.poll();
        SstableIterator.Record record = newest.next();

        // If the iterator we just polled still has data, put it back in the PQ
        if (newest.hasNext()) {
            pq.add(newest);
        } else {
            newest.close();
        }

        // Skip all older versions of the same key. This is the core "shadowing" logic.
        while (!pq.isEmpty() && ByteArrayComparator.INSTANCE.compare(pq.peek().peekKey(), record.key()) == 0) {
            PeekingIterator older = pq.poll();
            older.next(); // Advance and discard the shadowed record
            if (older.hasNext()) {
                pq.add(older);
            } else {
                older.close();
            }
        }

        return record;
    }

    /**
     * Closes the merging iterator and all underlying SSTable iterators.
     * <p>
     * This is critical to release off-heap memory mappings associated with the SSTables.
     */
    @Override
    public void close() {
        while (!pq.isEmpty()) {
            pq.poll().close();
        }
    }

    /**
     * Helper class that wraps an {@link SstableIterator} to allow peeking 
     * at the next record's key without advancing the iterator.
     * <p>
     * This wrapper is used as the element in the {@link PriorityQueue}.
     */
    private static class PeekingIterator implements AutoCloseable {
        /** The underlying SSTable iterator. */
        private final SstableIterator it;
        /** The relative age of this iterator's source (0 is newest). */
        private final int sequence;
        /** Cached next record for peeking. */
        private SstableIterator.Record next;

        /**
         * Constructs a {@code PeekingIterator}.
         *
         * @param it       the underlying SSTable iterator.
         * @param sequence the sequence number representing the age of the SSTable.
         */
        public PeekingIterator(SstableIterator it, int sequence) {
            this.it = it;
            this.sequence = sequence;
            this.next = it.next();
        }

        /** 
         * Returns the key of the next record without advancing the iterator. 
         * @return the next record key.
         */
        public byte[] peekKey() {
            return next.key();
        }

        /** 
         * Checks if the underlying iterator has more records. 
         * @return {@code true} if a record is available.
         */
        public boolean hasNext() {
            return next != null;
        }

        /** 
         * Returns the next record and advances the underlying iterator. 
         * @return the next record.
         */
        public SstableIterator.Record next() {
            SstableIterator.Record ret = next;
            next = it.hasNext() ? it.next() : null;
            return ret;
        }

        /** 
         * Closes the underlying SSTable iterator. 
         */
        @Override
        public void close() {
            it.close();
        }
    }
}
