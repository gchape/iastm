package tech.provokedynamic.iastm.atomic;

import lombok.EqualsAndHashCode;
import tech.provokedynamic.iastm.clock.TVarClock;
import tech.provokedynamic.iastm.mvcc.VersionHistory;

import java.util.concurrent.locks.ReentrantLock;

import static tech.provokedynamic.iastm.atomic.IASTM.__STRATEGY;
import static tech.provokedynamic.iastm.atomic.IASTM.__TX;

/// A transactional variable — the fundamental mutable cell of the IASTM system.
///
/// Each `TVar` holds a single value that can only be read or written within
/// an active transaction via [IASTM#read(TVar)] and [IASTM#write(TVar, Object)].
/// Internally it maintains a [VersionHistory] ring-buffer for MVCC snapshot reads
/// under the optimistic strategy, and a [java.util.concurrent.locks.ReentrantLock]
/// for ownership stealing under the pessimistic strategy.
///
/// `TVar` instances are totally ordered by their monotonic [#id] so that lock
/// acquisition always proceeds in a canonical order, preventing deadlocks.
///
/// @param <T> the type of value stored in this transactional variable
@EqualsAndHashCode(of = "id")
public final class TVar<T> implements Comparable<TVar<T>> {

    /// Globally unique, monotonically increasing identity assigned at construction time.
    /// Used as the sort key for deadlock-free lock ordering.
    final long id = TVarClock.INSTANCE.next();

    /// Per-variable reentrant lock. Acquired eagerly in pessimistic mode (ownership
    /// stealing) and acquired at commit time in optimistic mode.
    final ReentrantLock lock = new ReentrantLock();

    /// Ring-buffer of up to 32 past (value, version) pairs, enabling snapshot reads
    /// without holding any lock in optimistic mode.
    private final VersionHistory<T> history;

    /// The current committed value. Written under lock during commit; read speculatively
    /// in pessimistic mode.
    volatile T value;

    /// The global clock stamp of the last committed write to this variable.
    volatile long version;

    /// Constructs a new `TVar` with the given initial value at version 0.
    ///
    /// @param initial the value visible to all transactions before the first write
    public TVar(T initial) {
        this.version = 0L;
        this.value = initial;
        this.history = VersionHistory.of(initial);
    }

    /// Reads this variable in the context of the current transaction.
    ///
    /// In **optimistic** mode the read is served from [VersionHistory#scan(long)]
    /// at the transaction's `readPoint`, providing a consistent snapshot without
    /// acquiring any lock. In **pessimistic** mode the live `value` field is
    /// returned directly (ownership was already stolen at write time).
    /// Either way the (variable, version) pair is recorded in the transaction's
    /// read-set for validation at commit.
    ///
    /// @param readPoint the logical timestamp at which the enclosing transaction started
    /// @return the value of this variable visible at `readPoint`
    T read(long readPoint) {
        if (__STRATEGY.get() == IASTM.Strategy.PESSIMISTIC) {
            __TX.get().read(this, this.version);
            return value;
        }
        __TX.get().read(this, this.version);
        return history.scan(readPoint);
    }

    /// Stages write to this variable in the current transaction's write-set.
    ///
    /// In pessimistic mode, ownership of the lock is stolen immediately so that
    /// no other transaction can write concurrently. The value is not applied to
    /// `value` or `history` until [Tx#commit()] succeeds.
    ///
    /// @param val the new value to commit if the transaction succeeds
    void write(T val) {
        __TX.get().write(this, val);
    }

    /// Attempts to acquire this variable's lock without blocking.
    ///
    /// Used during pessimistic ownership stealing. Returns `false` immediately
    /// if another thread already holds the lock, triggering an abort and retry.
    ///
    /// @return `true` if the lock was acquired by the calling thread
    boolean tryAcquireOwnership() {
        return lock.tryLock();
    }

    /// Releases this variable's lock if it is held by the current thread.
    ///
    /// Called during abort cleanup to undo all ownership steals performed by
    /// a pessimistic transaction that could not commit.
    void unsteal() {
        if (lock.isHeldByCurrentThread()) lock.unlock();
    }

    /// Atomically applies a committed write: updates `value`, `version`, and appends
    /// to the MVCC history ring-buffer.
    ///
    /// Must be called while the lock is held (either stolen pessimistically or
    /// acquired at commit time optimistically).
    ///
    /// @param nVal     the new committed value
    /// @param nVersion the global clock stamp assigned to this commit batch
    @SuppressWarnings("unchecked")
    void commit(Object nVal, long nVersion) {
        this.value = (T) nVal;
        this.version = nVersion;
        history.append((T) nVal, nVersion);
    }

    /// Orders `TVar` instances by their unique [#id] for deadlock-free lock acquisition.
    @Override
    public int compareTo(TVar<T> tVar) {
        return Long.compare(this.id, tVar.id);
    }
}
