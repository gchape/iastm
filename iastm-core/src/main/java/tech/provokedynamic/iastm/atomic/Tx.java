package tech.provokedynamic.iastm.atomic;

import lombok.AccessLevel;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import tech.provokedynamic.iastm.clock.TxClock;

import java.util.*;

import static tech.provokedynamic.iastm.atomic.IASTM.__STRATEGY;

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

    @SuppressWarnings("unchecked")
    <T> T read(TVar<T> tvar) {
        if (ws.containsKey(tvar)) {
            return (T) ws.get(tvar);
        }
        if (__STRATEGY.get() == IASTM.Strategy.PESSIMISTIC) {
            rs.putIfAbsent(tvar, tvar.version);
            return tvar.value;
        }
        long version = tvar.version;
        if (version > readPoint) {
            throw new ConcurrentModificationException();
        }
        if (tvar.lock.isLocked()) {
            throw new ConcurrentModificationException();
        }
        T val = tvar.history.scan(readPoint);
        if (tvar.version != version) {
            throw new ConcurrentModificationException();
        }
        rs.putIfAbsent(tvar, version);
        return val;
    }

    <T> void write(TVar<T> tvar, T val) {
        if (__STRATEGY.get() == IASTM.Strategy.PESSIMISTIC) {
            steal(tvar);
        }
        ws.put(tvar, val);
    }

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

    private void commitOptimistic() {
        ws.keySet().forEach(t -> t.lock.lock());
        try {
            validateReads();
            applyWrites();
        } finally {
            ws.keySet().forEach(t -> t.lock.unlock());
        }
    }

    private void validateReads() {
        rs.forEach((tvar, rv) -> {
            if (!tvar.lock.isHeldByCurrentThread() && tvar.lock.isLocked()) {
                throw new ConcurrentModificationException();
            }
            if (tvar.version != rv) {
                log.debug("read conflict tvar={} expected={} actual={}",
                        System.identityHashCode(tvar), rv, tvar.version);
                throw new ConcurrentModificationException();
            }
        });
    }

    @SuppressWarnings("unchecked")
    private void applyWrites() {
        long version = TxClock.INSTANCE.next();
        log.debug("applying writes commitVersion={} count={} strategy={}",
                version, ws.size(), __STRATEGY.get());
        for (var e : ws.entrySet()) {
            TVar<Object> tVar = (TVar<Object>) e.getKey();
            Object val = e.getValue();
            tVar.commit(val, version);
        }
    }

    private void steal(TVar<?> tvar) {
        if (tvar.lock.isHeldByCurrentThread()) {
            return;
        }
        if (!tvar.trySteal()) {
            unstealAll();
            throw new ConcurrentModificationException();
        }
        stolen.add(tvar);
    }

    private void unstealAll() {
        stolen.forEach(TVar::unsteal);
        stolen.clear();
    }

    @Override
    public int compareTo(Tx tx) {
        return Long.compare(this.readPoint, tx.readPoint);
    }
}
