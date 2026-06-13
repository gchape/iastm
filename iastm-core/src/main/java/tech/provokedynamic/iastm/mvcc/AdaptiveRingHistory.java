package tech.provokedynamic.iastm.mvcc;

import tech.provokedynamic.iastm.exception.VersionEvictedException;

import java.lang.invoke.MethodHandles;
import java.lang.invoke.VarHandle;

public final class AdaptiveRingHistory<T> implements AdaptiveHistory<T> {

    /// Minimum ring-buffer capacity.
    private static final int MIN_HISTORY = 32;

    /// Maximum ring-buffer capacity.
    private static final int MAX_HISTORY = 1024;

    /// Monotonically increasing write counter; slot = `cursor % size`.
    private final Cursor cursor = new Cursor();

    /// Current ring-buffer capacity; not thread-safe — resize must not be called concurrently.
    private int size;

    /// Global clock stamps, one per slot; indexed by `seq % size`.
    private long[] versions;

    /// Committed values, one per slot.
    private Object[] values;

    public AdaptiveRingHistory(T initial) {
        this.size = MIN_HISTORY;
        this.versions = new long[MIN_HISTORY];
        this.values = new Object[MIN_HISTORY];
        this.versions[0] = 0L;
        this.values[0] = initial;
    }

    @Override
    public void append(T val, long version) {
        long c = cursor.advance();
        int slot = (int) (c % size);
        versions[slot] = version;
        values[slot] = val;
    }

    @Override
    public void expandByFactor(float factor) {
        int next = Math.min(MAX_HISTORY, (int) (size * factor));
        if (next > size) resize(next);
    }

    @Override
    public void shrinkByFactor(float factor) {
        int next = Math.max(MIN_HISTORY, (int) (size * factor));
        if (next < size) resize(next);
    }

    private void resize(int newSize) {
        long c = cursor.now();
        int count = (int) Math.min(Math.min(c + 1, size), newSize);
        long[] newVersions = new long[newSize];
        Object[] newValues = new Object[newSize];
        for (int i = 0; i < count; i++) {
            long s = c - count + 1 + i;
            newVersions[(int) (s % newSize)] = versions[(int) (s % size)];
            newValues[(int) (s % newSize)] = values[(int) (s % size)];
        }
        this.versions = newVersions;
        this.values = newValues;
        this.size = newSize;
    }

    @SuppressWarnings("unchecked")
    @Override
    public T scan(long readPoint) {
        long hi = cursor.now();
        long lo = Math.max(0, hi - size + 1);
        long best = -1;
        while (lo <= hi) {
            long mid = (lo + hi) >>> 1;
            int slot = (int) (mid % size);
            if (versions[slot] <= readPoint) {
                best = mid;
                lo = mid + 1;
            } else {
                hi = mid - 1;
            }
        }
        if (best == -1) throw new VersionEvictedException(readPoint);
        return (T) values[(int) (best % size)];
    }

    private static class Cursor {

        private static final VarHandle COUNTER;

        static {
            try {
                COUNTER = MethodHandles.lookup()
                        .findVarHandle(Cursor.class, "counter", long.class);
            } catch (ReflectiveOperationException e) {
                throw new ExceptionInInitializerError(e);
            }
        }

        @SuppressWarnings("FieldMayBeFinal")
        private volatile long counter = 0L;

        public long now() {
            return counter;
        }

        public long advance() {
            return (long) COUNTER.getAndAdd(this, 1L) + 1L;
        }
    }
}
