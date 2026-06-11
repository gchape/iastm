package tech.provokedynamic.iastm.clock;

import java.util.concurrent.atomic.AtomicLong;

/// Global identity counter for [tech.provokedynamic.iastm.atomic.TVar] instances.
///
/// Each `TVar` calls [#next()] exactly once during construction to obtain a
/// unique, monotonically increasing `id`. This id is the sole basis for the
/// total order defined by [tech.provokedynamic.iastm.atomic.TVar#compareTo],
/// which in turn determines the lock-acquisition order in
/// [tech.provokedynamic.iastm.atomic.Tx#commitOptimistic()] to prevent deadlocks.
public enum TVarClock {
    INSTANCE;

    private final AtomicLong version = new AtomicLong(0L);

    /// Returns the next unique `TVar` identity value.
    ///
    /// @return a strictly increasing long that has never been returned before
    public long next() {
        return version.incrementAndGet();
    }
}
