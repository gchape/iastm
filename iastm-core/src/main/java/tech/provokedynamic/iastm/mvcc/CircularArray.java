package tech.provokedynamic.iastm.mvcc;

import tech.provokedynamic.iastm.exception.VersionEvictedException;

public final class CircularArray<T> implements AdaptiveHistory<T> {

    private static final int INITIAL_CAPACITY = 32;
    private static final int MAX_CAPACITY = 2048;

    private long[] versions;
    private Object[] values;
    private long head;

    public CircularArray(T initial) {
        versions = new long[INITIAL_CAPACITY];
        values = new Object[INITIAL_CAPACITY];
        versions[0] = 0L;
        values[0] = initial;
        head = 1L;
    }

    @Override
    public void append(T val, long version) {
        int idx = (int) (head % values.length);
        values[idx] = val;
        versions[idx] = version;
        head++;
    }

    @Override
    @SuppressWarnings("unchecked")
    public T scan(long rv) {
        long h = head;
        int size = values.length;
        long count = Math.min(h, size);
        long lo = h - count;
        long hi = h - 1;

        if (rv < versions[(int) (lo % size)]) {
            throw new VersionEvictedException(rv);
        }

        while (lo < hi) {
            long mid = lo + ((hi - lo + 1) >>> 1);
            if (versions[(int) (mid % size)] <= rv) {
                lo = mid;
            } else {
                hi = mid - 1;
            }
        }
        return (T) values[(int) (lo % size)];
    }

    @Override
    public void expandByFactor(float delta) {
        int size = values.length;
        int nsize = Math.min((int) (size * delta), MAX_CAPACITY);
        if (nsize > size) resize(nsize);
    }

    @Override
    public void shrinkByFactor(float delta) {
        int size = values.length;
        int nsize = Math.max((int) (size * delta), INITIAL_CAPACITY);
        if (nsize < size) resize(nsize);
    }

    private void resize(int nsize) {
        int size = values.length;
        long[] nversions = new long[nsize];
        Object[] nvalues = new Object[nsize];

        long count = Math.min(head, nsize);
        long start = head - count;

        for (long i = 0; i < count; i++) {
            long idx = start + i;
            int src = (int) (idx % size);
            int dst = (int) (idx % nsize);
            nvalues[dst] = values[src];
            nversions[dst] = versions[src];
        }

        values = nvalues;
        versions = nversions;
    }
}
