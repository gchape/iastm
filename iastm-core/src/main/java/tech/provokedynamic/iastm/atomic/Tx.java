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

    final long readPoint = TxClock.INSTANCE.current();

    private final HashMap<TVar<?>, Long> rs = new HashMap<>();
    private final TreeMap<TVar<?>, Object> ws = new TreeMap<>();

    private final List<TVar<?>> stolen = new ArrayList<>();

    <T> void read(TVar<T> tvar, long version) {
        rs.putIfAbsent(tvar, version);
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
            long live = tvar.version;
            if (rv != live) {
                log.debug("read conflict tvar={} expected={} actual={}",
                        System.identityHashCode(tvar), rv, live);
                throw new ConcurrentModificationException();
            }
        });
    }

    private void applyWrites() {
        long nVersion = TxClock.INSTANCE.next();
        log.debug("applying writes commitVersion={} count={} strategy={}",
                nVersion, ws.size(), __STRATEGY.get());
        ws.forEach((tvar, nVal) -> tvar.commit(nVal, nVersion));
    }

    private void steal(TVar<?> tvar) {
        if (tvar.lock.isHeldByCurrentThread()) return;
        if (!tvar.tryAcquireOwnership()) {
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
