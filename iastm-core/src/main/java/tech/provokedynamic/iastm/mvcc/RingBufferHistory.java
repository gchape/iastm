package tech.provokedynamic.iastm.mvcc;

import tech.provokedynamic.iastm.exception.VersionEvictedException;

import java.lang.invoke.MethodHandles;
import java.lang.invoke.VarHandle;
import java.util.concurrent.atomic.AtomicLong;

public final class RingBufferHistory<T> implements History<T> {

    /// Ring-buffer capacity; must be a power of two.
    private static final int MAX_HISTORY = 32;

    /// Slot mask: `seq & MASK` maps any sequence number to a valid index.
    private static final int MASK = MAX_HISTORY - 1;

    /// VarHandle for `setRelease`/`getAcquire` access to the values array.
    private static final VarHandle VALUES = MethodHandles.arrayElementVarHandle(Object[].class);

    /// Global clock stamps, one per slot.
    private final long[] versions = new long[MAX_HISTORY];

    /// Committed values, one per slot; accessed only through [#VALUES].
    private final Object[] values = new Object[MAX_HISTORY];

    /// Monotonically increasing write counter; slot = `cursor & MASK`.
    private final AtomicLong cursor = new AtomicLong(-1L);

    public RingBufferHistory(T initial) {
        cursor.set(0L);
        versions[0] = 0L;
        VALUES.setRelease(values, 0, initial);
    }

    public void append(T val, long version) {
        long c = cursor.incrementAndGet();
        int slot = (int) (c & MASK);
        versions[slot] = version;
        VALUES.setRelease(values, slot, val);
    }

    @SuppressWarnings("unchecked")
    public T scan(long readPoint) {
        long c = cursor.get();
        long limit = c - MAX_HISTORY + 1;
        for (long seq = c; seq >= limit && seq >= 0; seq--) {
            int slot = (int) (seq & MASK);
            T val = (T) VALUES.getAcquire(values, slot);
            if (val != null && versions[slot] <= readPoint) {
                return val;
            }
        }
        throw new VersionEvictedException(readPoint);
    }
}
