package tech.provokedynamic.iastm.atomic;

import lombok.EqualsAndHashCode;
import tech.provokedynamic.iastm.mvcc.AdaptiveHistory;
import tech.provokedynamic.iastm.mvcc.AdaptiveRingHistory;

import java.lang.invoke.MethodHandles;
import java.lang.invoke.VarHandle;
import java.util.concurrent.locks.ReentrantLock;

@EqualsAndHashCode(of = "id")
public final class TVar<T> implements Comparable<TVar<T>> {

    /// Ring-buffer of up to 32 past (value, version) pairs, enabling snapshot reads
    /// without holding any lock in optimistic mode.
    final AdaptiveHistory<T> history;

    /// Globally unique, monotonically increasing identity assigned at construction time.
    /// Used as the sort key for deadlock-free lock ordering.
    final long id = Clock.INSTANCE.advance();

    /// Per-variable reentrant lock. Acquired eagerly in pessimistic mode (ownership
    /// stealing) and acquired at commit time in optimistic mode.
    final ReentrantLock lock = new ReentrantLock();

    /// The current committed value. Written under lock during commit; read speculatively
    /// in pessimistic mode.
    volatile T value;

    /// The global clock stamp of the last committed write to this variable.
    volatile long version;

    public TVar(T initial) {
        this.version = 0L;
        this.value = initial;
        this.history = new AdaptiveRingHistory<>(initial);
    }

    void commit(T val, long version) {
        this.value = val;
        this.version = version;
        history.append(val, version);
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

    private enum Clock {
        INSTANCE;

        private static final VarHandle VERSION;

        static {
            try {
                VERSION = MethodHandles.lookup()
                        .findVarHandle(TVar.Clock.class, "version", long.class);
            } catch (ReflectiveOperationException e) {
                throw new ExceptionInInitializerError(e);
            }
        }

        @SuppressWarnings({"FieldMayBeFinal", "NonFinalFieldInEnum"})
        private volatile long version = 0L;

        public long advance() {
            return (long) VERSION.getAndAdd(this, 1L) + 1L;
        }
    }
}
