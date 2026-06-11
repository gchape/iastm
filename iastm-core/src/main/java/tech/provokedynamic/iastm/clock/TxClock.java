package tech.provokedynamic.iastm.clock;

import java.util.concurrent.atomic.AtomicLong;

/// Global logical clock for transaction timestamps.
///
/// Every transaction samples [#current()] at creation to obtain its `readPoint`,
/// and every commit batch advances the clock with [#next()] to obtain a unique
/// commit version. The monotonically increasing sequence guarantees that a
/// transaction's snapshot always precedes the versions it commits, which is the
/// fundamental safety invariant of snapshot isolation.
///
/// Implemented as a lock-free [AtomicLong] singleton to minimize contention on
/// the hot path.
public enum TxClock {
    INSTANCE;

    private final AtomicLong global = new AtomicLong(0L);

    /// Returns the current clock value without advancing it.
    ///
    /// Used by [tech.provokedynamic.iastm.atomic.Tx] to capture the `readPoint`
    /// at the start of each transaction attempt.
    ///
    /// @return the latest committed version visible to new transactions
    public long current() {
        return global.get();
    }

    /// Atomically increments the clock and returns the new value.
    ///
    /// Called once per successful commit to stamp all variables written in that
    /// batch with the same version, ensuring multi-variable atomicity.
    ///
    /// @return a strictly greater version than any previously returned value
    public long next() {
        return global.incrementAndGet();
    }
}
