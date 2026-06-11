package tech.provokedynamic.iastm.clock;

import java.util.concurrent.atomic.AtomicLong;

/// Global logical clock for transaction timestamps.
///
/// Transactions sample [#current()] at creation for their `readPoint`; each
/// successful commit advances the clock via [#next()]. The monotonically
/// increasing sequence is the core safety invariant of snapshot isolation.
public enum TxClock {
    INSTANCE;

    private final AtomicLong global = new AtomicLong(0L);

    /// Returns the current clock value without advancing it.
    public long current() {
        return global.get();
    }

    /// Atomically increments and returns the new clock value.
    /// Called once per commit to assign a single version to the entire write batch.
    public long next() {
        return global.incrementAndGet();
    }
}
