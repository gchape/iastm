package tech.provokedynamic.iastm.mvcc;

import tech.provokedynamic.iastm.exception.VersionEvictedException;

import java.lang.invoke.MethodHandles;
import java.lang.invoke.VarHandle;
import java.util.concurrent.atomic.AtomicLong;

/// Lock-free MVCC version ring-buffer for a single [tech.provokedynamic.iastm.atomic.TVar].
///
/// Stores up to `MAX_HISTORY` (32) past (value, version) pairs in a fixed-size
/// circular array. Writers call [#append(Object, long)] to add new entries;
/// readers call [#scan(long)] to retrieve the latest value whose version does
/// not exceed their `readPoint`, providing point-in-time snapshot semantics
/// without any read-side locking.
///
/// Memory ordering is ensured via a [VarHandle] with `setRelease` / `getAcquire`
/// semantics on the values array, pairing with the `AtomicLong` cursor that acts
/// as the sequencing primitive.
///
/// Once the buffer wraps around, the oldest 32 entries are silently overwritten.
/// A [VersionEvictedException] is thrown if a reader requests a `readPoint` that
/// predates the oldest surviving entry, indicating the transaction must abort and
/// restart with a fresher snapshot.
///
/// @param <T> the value type stored alongside each version stamp
public final class VersionHistory<T> {

    /// Maximum number of versions retained per `TVar`.
    /// Must be a power of two so that `seq & MASK` gives the slot index.
    private static final int MAX_HISTORY = 32;

    /// Bitmask for mapping a sequence number to a ring-buffer slot.
    private static final int MASK = MAX_HISTORY - 1;

    /// `VarHandle` for the `values` array, enabling `setRelease`/`getAcquire`
    /// ordering without `volatile` array elements.
    private static final VarHandle VALUES = MethodHandles.arrayElementVarHandle(Object[].class);

    /// Parallel array of global clock stamps, one per slot.
    private final long[] versions = new long[MAX_HISTORY];

    /// Parallel array of committed values, one per slot.
    /// Accessed exclusively through [#VALUES] for correct visibility.
    private final Object[] values = new Object[MAX_HISTORY];

    /// Monotonically increasing write counter. The slot for sequence `s` is
    /// `s & MASK`. Starts at 0 after the initial value is seeded at slot 0.
    private final AtomicLong cursor = new AtomicLong(-1L);

    private VersionHistory(T initial) {
        versions[0] = 0L;
        VALUES.setRelease(values, 0, initial);
        cursor.set(0L);
    }

    /// Creates a new `VersionHistory` pre-seeded with `initial` at version 0.
    ///
    /// @param <T>     the value type
    /// @param initial the value visible to any transaction with `readPoint >= 0`
    /// @return a fresh history containing exactly one entry
    public static <T> VersionHistory<T> of(T initial) {
        return new VersionHistory<>(initial);
    }

    /// Appends a new (value, version) pair to the ring-buffer.
    ///
    /// The cursor is incremented first to claim a slot; then the version stamp
    /// is written before the value so that [#scan(long)] never observes a value
    /// paired with a stale version. `setRelease` ensures the value write is
    /// visible to any thread that subsequently performs a `getAcquire` on the
    /// same slot.
    ///
    /// @param val     the newly committed value
    /// @param version the global clock stamp assigned to this commit
    public void append(T val, long version) {
        long seq = cursor.incrementAndGet();
        int slot = (int) (seq & MASK);
        versions[slot] = version;
        VALUES.setRelease(values, slot, val);
    }

    /// Scans the ring-buffer backwards from the most recent entry to find the
    /// latest value whose version does not exceed `readPoint`.
    ///
    /// Iterates at most `MAX_HISTORY` slots. A slot is eligible if its value is
    /// non-null and `versions[slot] <= readPoint`. The first matching slot wins
    /// because the scan proceeds from newest to oldest.
    ///
    /// @param readPoint the transaction's snapshot timestamp
    /// @return the most recent value visible at `readPoint`
    /// @throws VersionEvictedException if all retained versions are newer than
    ///         `readPoint`, meaning the required snapshot has been overwritten
    @SuppressWarnings("unchecked")
    public T scan(long readPoint) {
        long c = cursor.get();
        long limit = c - MAX_HISTORY + 1;
        for (long seq = c; seq >= limit && seq >= 0; seq--) {
            int slot = (int) (seq & MASK);
            T val = (T) VALUES.getAcquire(values, slot);
            if (val != null && versions[slot] <= readPoint) return val;
        }
        throw new VersionEvictedException(readPoint);
    }
}
