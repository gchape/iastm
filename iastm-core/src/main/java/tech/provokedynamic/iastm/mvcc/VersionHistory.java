package tech.provokedynamic.iastm.mvcc;

import tech.provokedynamic.iastm.exception.VersionEvictedException;

import java.lang.invoke.MethodHandles;
import java.lang.invoke.VarHandle;
import java.util.concurrent.atomic.AtomicLong;

public final class VersionHistory<T> {

    private static final int MAX_HISTORY = 32;
    private static final int MASK = MAX_HISTORY - 1;

    private static final VarHandle VALUES = MethodHandles.arrayElementVarHandle(Object[].class);

    private final long[] versions = new long[MAX_HISTORY];
    private final Object[] values = new Object[MAX_HISTORY];
    private final AtomicLong cursor = new AtomicLong(-1L);

    private VersionHistory(T initial) {
        versions[0] = 0L;
        VALUES.setRelease(values, 0, initial);
        cursor.set(0L);
    }

    public static <T> VersionHistory<T> of(T initial) {
        return new VersionHistory<>(initial);
    }

    public void append(T val, long version) {
        long seq = cursor.incrementAndGet();
        int slot = (int) (seq & MASK);
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
            if (val != null && versions[slot] <= readPoint) return val;
        }
        throw new VersionEvictedException(readPoint);
    }
}
