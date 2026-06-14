package tech.provokedynamic.iastm.atomic;

import lombok.EqualsAndHashCode;
import tech.provokedynamic.iastm.mvcc.AdaptiveHistory;
import tech.provokedynamic.iastm.mvcc.CircularArray;

import java.util.concurrent.locks.ReentrantLock;

@EqualsAndHashCode(of = "id")
public final class TVar<T> implements Comparable<TVar<T>> {

    private static final Clock ID_CLOCK = new Clock();

    final AdaptiveHistory<T> history;

    final long id = ID_CLOCK.advance();

    final ReentrantLock lock = new ReentrantLock();

    volatile T value;
    volatile long version;

    public TVar(T initial) {
        this.version = 0L;
        this.value = initial;
        this.history = new CircularArray<>(initial);
    }

    void commit(T val, long stamp) {
        this.value = val;
        this.version = stamp;
        history.append(val, stamp);
    }

    boolean trySteal() {
        return lock.tryLock();
    }

    void unsteal() {
        if (lock.isHeldByCurrentThread()) lock.unlock();
    }

    @Override
    public int compareTo(TVar<T> other) {
        return Long.compare(this.id, other.id);
    }
}
