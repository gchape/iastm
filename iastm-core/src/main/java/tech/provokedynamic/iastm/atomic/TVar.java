package tech.provokedynamic.iastm.atomic;

import lombok.EqualsAndHashCode;
import tech.provokedynamic.iastm.clock.TVarClock;
import tech.provokedynamic.iastm.mvcc.VersionHistory;

import java.util.concurrent.locks.ReentrantLock;

import static tech.provokedynamic.iastm.atomic.IASTM.__STRATEGY;
import static tech.provokedynamic.iastm.atomic.IASTM.__TX;

@EqualsAndHashCode(of = "id")
public final class TVar<T> implements Comparable<TVar<T>> {

    final long id = TVarClock.INSTANCE.next();
    final ReentrantLock lock = new ReentrantLock();

    private final VersionHistory<T> history;

    volatile T value;
    volatile long version;

    public TVar(T initial) {
        this.version = 0L;
        this.value = initial;
        this.history = VersionHistory.of(initial);
    }

    T read(long readPoint) {
        if (__STRATEGY.get() == IASTM.Strategy.PESSIMISTIC) {
            __TX.get().read(this, this.version);
            return value;
        }
        __TX.get().read(this, this.version);
        return history.scan(readPoint);
    }

    void write(T val) {
        __TX.get().write(this, val);
    }

    boolean tryAcquireOwnership() {
        return lock.tryLock();
    }

    void unsteal() {
        if (lock.isHeldByCurrentThread()) lock.unlock();
    }

    @SuppressWarnings("unchecked")
    void commit(Object nVal, long nVersion) {
        this.value = (T) nVal;
        this.version = nVersion;
        history.append((T) nVal, nVersion);
    }

    @Override
    public int compareTo(TVar<T> tVar) {
        return Long.compare(this.id, tVar.id);
    }
}
