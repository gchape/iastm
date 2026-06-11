package tech.provokedynamic.iastm.clock;

import java.util.concurrent.atomic.AtomicLong;

/// Global identity counter for [TVar] instances.
///
/// Each `TVar` calls [#next()] once at construction. The resulting id is the
/// sole basis for [TVar#compareTo], which determines lock-acquisition order
/// in [Tx#commitOptimistic()] to prevent deadlocks.
public enum TVarClock {
    INSTANCE;

    private final AtomicLong version = new AtomicLong(0L);

    /// Returns the next unique `TVar` id; strictly increasing, never repeated.
    public long next() {
        return version.incrementAndGet();
    }
}
