package tech.provokedynamic.iastm.atomic;

import lombok.AccessLevel;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import tech.provokedynamic.iastm.clock.TxClock;

import java.util.*;

import static tech.provokedynamic.iastm.atomic.IASTM.__STRATEGY;

/// Represents a single attempt of a transaction.
///
/// A `Tx` is created at the start of each attempt inside [IASTM#run(Runnable)]
/// and discarded after commit or abort. It tracks:
///
/// - **read-set** (`rs`) — every [TVar] touched by a read, with the version seen
/// - **write-set** (`ws`) — every [TVar] to be updated, sorted by [TVar#id] for
///   deadlock-free lock acquisition
/// - **stolen list** — `TVar`s whose locks were acquired eagerly in pessimistic mode
///
/// Two commit paths exist:
///
/// - [#commitOptimistic()] — acquires write-set locks at commit time, validates
///   reads, then applies writes
/// - [#commitPessimistic()] — locks were already stolen; only validates and applies
@Slf4j
@EqualsAndHashCode(of = "readPoint")
@NoArgsConstructor(access = AccessLevel.PACKAGE)
public final class Tx implements Comparable<Tx> {

    /// The global clock value sampled when this transaction attempt was created.
    /// All snapshot reads use this timestamp to select a consistent version from
    /// each [TVar]'s [tech.provokedynamic.iastm.mvcc.VersionHistory].
    final long readPoint = TxClock.INSTANCE.current();

    /// Read-set: maps each [TVar] to the version observed during the read.
    /// Used by [#validateReads()] to detect concurrent writes before commit.
    private final HashMap<TVar<?>, Long> rs = new HashMap<>();

    /// Write-set: maps each [TVar] to its pending new value, sorted by [TVar#id]
    /// to ensure a globally consistent lock-acquisition order.
    private final TreeMap<TVar<?>, Object> ws = new TreeMap<>();

    /// Tracks `TVar`s whose locks were stolen by [#steal(TVar)] in pessimistic mode
    /// so they can all be released by [#unstealAll()] on abort.
    private final List<TVar<?>> stolen = new ArrayList<>();

    /// Records a read of `tvar` at the given `version` in the read-set.
    ///
    /// Uses `putIfAbsent` so that the first observed version is preserved if the
    /// same variable is read multiple times within one transaction.
    ///
    /// @param tvar    the variable that was read
    /// @param version the version of `tvar` at the time of the read
    <T> void read(TVar<T> tvar, long version) {
        rs.putIfAbsent(tvar, version);
    }

    /// Stages write to `Tvar` in the write-set.
    ///
    /// In pessimistic mode, [#steal(TVar)] is called first to acquire the lock
    /// immediately, aborting the transaction if the lock is already held elsewhere.
    ///
    /// @param tvar the variable to write
    /// @param val  the value to apply if the transaction commits
    <T> void write(TVar<T> tvar, T val) {
        if (__STRATEGY.get() == IASTM.Strategy.PESSIMISTIC) {
            steal(tvar);
        }
        ws.put(tvar, val);
    }

    /// Attempts to commit this transaction.
    ///
    /// Read-only transactions (empty write-set) are committed trivially.
    /// Otherwise, delegates to [#commitPessimistic()] or [#commitOptimistic()]
    /// depending on the active [IASTM.Strategy].
    void commit() {
        if (ws.isEmpty()) {
            log.debug("commit skipped — read-only tx");
            return;
        }
        log.debug("commit attempt reads={} writes={}", rs.size(), ws.size());
        if (__STRATEGY.get() == IASTM.Strategy.PESSIMISTIC) {
            commitPessimistic();
        } else {
            commitOptimistic();
        }
    }

    /// Commits under pessimistic strategy.
    ///
    /// All write-set locks were already acquired by [#steal(TVar)]; this path
    /// only validates reads and applies writes, releasing the stolen locks afterward.
    /// On read conflict the locks are released before re-throwing so no thread is
    /// left starved.
    private void commitPessimistic() {
        try {
            validateReads();
            applyWrites();
        } catch (ConcurrentModificationException e) {
            unstealAll();
            throw e;
        }
        unstealAll();
    }

    /// Commits under optimistic strategy.
    ///
    /// Acquires all write-set locks in [TVar#id] order (guaranteed by the
    /// [TreeMap] ordering), then validates reads, applies writes, and releases
    /// all locks in a `finally` block.
    private void commitOptimistic() {
        ws.keySet().forEach(t -> t.lock.lock());
        try {
            validateReads();
            applyWrites();
        } finally {
            ws.keySet().forEach(t -> t.lock.unlock());
        }
    }

    /// Checks that every variable in the read-set still carries the version
    /// that was observed when it was read.
    ///
    /// A mismatch means another transaction committed write between our read
    /// and our commit attempt, so this transaction must abort.
    ///
    /// @throws java.util.ConcurrentModificationException if any read-set entry
    ///         has been updated by a concurrent transaction
    private void validateReads() {
        rs.forEach((tvar, rv) -> {
            long live = tvar.version;
            if (rv != live) {
                log.debug("read conflict tvar={} expected={} actual={}",
                        System.identityHashCode(tvar), rv, live);
                throw new ConcurrentModificationException();
            }
        });
    }

    /// Applies all buffered writes atomically under the global clock.
    ///
    /// A single new version stamp is obtained from [TxClock] and assigned to
    /// every variable in the write-set, preserving the "all-or-nothing" version
    /// invariant across multi-variable commits.
    private void applyWrites() {
        long nVersion = TxClock.INSTANCE.next();
        log.debug("applying writes commitVersion={} count={} strategy={}",
                nVersion, ws.size(), __STRATEGY.get());
        ws.forEach((tvar, nVal) -> tvar.commit(nVal, nVersion));
    }

    /// Attempts to acquire `tvar`'s lock on behalf of the current pessimistic transaction.
    ///
    /// If the lock is already held by this thread (re-entrant write) the method
    /// returns immediately. If the `tryLock` fails, all previously stolen locks
    /// are released and a [java.util.ConcurrentModificationException] is thrown to
    /// trigger a retry.
    ///
    /// @param tvar the variable whose lock should be stolen
    private void steal(TVar<?> tvar) {
        if (tvar.lock.isHeldByCurrentThread()) return;
        if (!tvar.tryAcquireOwnership()) {
            unstealAll();
            throw new ConcurrentModificationException();
        }
        stolen.add(tvar);
    }

    /// Releases all locks stolen during this transaction attempt and clears the
    /// stolen list so the `Tx` is safe to discard.
    private void unstealAll() {
        stolen.forEach(TVar::unsteal);
        stolen.clear();
    }

    /// Orders transactions by their [#readPoint] for deterministic comparisons.
    @Override
    public int compareTo(Tx tx) {
        return Long.compare(this.readPoint, tx.readPoint);
    }
}
