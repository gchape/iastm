package tech.provokedynamic.iastm.atomic;

import lombok.extern.slf4j.Slf4j;
import tech.provokedynamic.iastm.clock.TVarClock;
import tech.provokedynamic.iastm.exception.VersionEvictedException;

import java.util.ArrayList;
import java.util.concurrent.locks.StampedLock;

import static tech.provokedynamic.iastm.atomic.IASTM.__TX;

@Slf4j
public final class TVar<T> {

    private static final int MAX_HISTORY = 32;

    final long id = TVarClock.INSTANCE.next();
    final StampedLock lock = new StampedLock();

    private final ArrayList<T> values = new ArrayList<>(MAX_HISTORY);
    private final ArrayList<Long> versions = new ArrayList<>(MAX_HISTORY);

    volatile T value;
    volatile long version;

    public TVar(T initial) {
        this.value = initial;
        this.version = 0L;
        values.add(initial);
        versions.add(0L);
    }

    T read(long readPoint) {
        long stamp = lock.readLock();
        try {
            long $version = this.version;
            __TX.get().read(this, $version);
            return scan(readPoint);
        } finally {
            lock.unlockRead(stamp);
        }
    }

    void write(T val) {
        __TX.get().write(this, val);
    }

    @SuppressWarnings("unchecked")
    void commit(Object nVal, long nVersion) {
        this.value = (T) nVal;
        this.version = nVersion;
        if (values.size() >= MAX_HISTORY) {
            values.removeFirst();
            versions.removeFirst();
        }
        values.add((T) nVal);
        versions.add(nVersion);
    }

    private T scan(long readPoint) {
        for (int i = versions.size() - 1; i >= 0; i--) {
            if (versions.get(i) <= readPoint) return values.get(i);
        }
        throw new VersionEvictedException(readPoint);
    }
}
