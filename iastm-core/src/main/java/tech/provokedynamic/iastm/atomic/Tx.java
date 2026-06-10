package tech.provokedynamic.iastm.atomic;

import lombok.AccessLevel;
import lombok.NoArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import tech.provokedynamic.iastm.clock.TxClock;

import java.util.Comparator;
import java.util.ConcurrentModificationException;
import java.util.HashMap;
import java.util.TreeMap;

import static tech.provokedynamic.iastm.atomic.IASTM.__STRATEGY;

@Slf4j
@NoArgsConstructor(access = AccessLevel.PACKAGE)
public final class Tx {

    final long readPoint = TxClock.INSTANCE.current();

    private final HashMap<TVar<?>, Long> rs = new HashMap<>();
    private final TreeMap<TVar<?>, Object> ws = new TreeMap<>(Comparator.comparingLong(tvar -> tvar.id));

    void read(TVar<?> tvar, long version) {
        rs.putIfAbsent(tvar, version);
    }

    void write(TVar<?> tvar, Object val) {
        ws.put(tvar, val);
    }

    void commit() {
        if (ws.isEmpty()) {
            log.debug("commit skipped — read-only tx");
            return;
        }
        log.debug("commit attempt reads={} writes={}", rs.size(), ws.size());

        long[] stamps = new long[ws.size()];
        int i = 0;
        for (TVar<?> tvar : ws.keySet()) {
            stamps[i++] = tvar.lock.writeLock();
        }
        try {
            validateReads();
            if (__STRATEGY.get() == IASTM.Strategy.OPTIMISTIC) {
                validateWriteVersions();
            }
            applyWrites();
        } finally {
            i = 0;
            for (TVar<?> tvar : ws.keySet()) {
                tvar.lock.unlockWrite(stamps[i++]);
            }
        }
    }

    private void validateReads() {
        for (var entry : rs.entrySet()) {
            TVar<?> tvar = entry.getKey();
            long rv = entry.getValue();
            if (rv != tvar.version) {
                log.debug("read conflict tvar={} expected={} actual={}",
                        System.identityHashCode(tvar), rv, tvar.version);
                throw new ConcurrentModificationException();
            }
        }
    }

    private void validateWriteVersions() {
        for (TVar<?> tvar : ws.keySet()) {
            if (tvar.version > readPoint) {
                log.debug("write conflict tvar={} version={} readPoint={}",
                        System.identityHashCode(tvar), tvar.version, readPoint);
                throw new ConcurrentModificationException();
            }
        }
    }

    private void applyWrites() {
        long nVersion = TxClock.INSTANCE.next();
        log.debug("applying writes commitVersion={} count={} strategy={}",
                nVersion, ws.size(), __STRATEGY.get());
        ws.forEach((tvar, nVal) -> tvar.commit(nVal, nVersion));
    }
}
