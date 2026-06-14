package tech.provokedynamic.iastm.atomic;

import java.util.ConcurrentModificationException;
import java.util.List;
import java.util.Map;

import static tech.provokedynamic.iastm.atomic.Protocol.OPTIMISTIC.apply;

public sealed interface Protocol {

    default void validateReads(Map<TVar<?>, Long> reads) {
        reads.forEach((tVar, rv) -> {
            if (!tVar.lock.isHeldByCurrentThread() && tVar.lock.isLocked()) {
                throw new ConcurrentModificationException();
            }
            if (tVar.version != rv) {
                throw new ConcurrentModificationException();
            }
        });
    }

    enum OPTIMISTIC implements Protocol {
        INSTANCE();

        @SuppressWarnings("unchecked")
        static void apply(Map<TVar<?>, Object> writes) {
            long cv = Tx.COMMIT_CLOCK.advance();
            writes.forEach((tvar, val) -> ((TVar<Object>) tvar).commit(val, cv));
        }

        <T> T read(TVar<T> tVar, long rv, Map<TVar<?>, Long> reads) {
            long v = tVar.version;
            if (v > rv || tVar.lock.isLocked()) {
                throw new ConcurrentModificationException();
            }
            T val = tVar.history.scan(rv);
            if (tVar.version != v) {
                throw new ConcurrentModificationException();
            }
            reads.putIfAbsent(tVar, v);
            return val;
        }

        void commit(Map<TVar<?>, Long> reads, Map<TVar<?>, Object> writes) {
            writes.keySet().forEach(t -> t.lock.lock());
            try {
                validateReads(reads);
                apply(writes);
            } finally {
                writes.keySet().forEach(t -> t.lock.unlock());
            }
        }
    }

    enum PESSIMISTIC_WRITE implements Protocol {
        INSTANCE();

        <T> T read(TVar<T> tvar, Map<TVar<?>, Long> reads) {
            reads.putIfAbsent(tvar, tvar.version);
            return tvar.value;
        }

        <T> void write(TVar<T> tVar, T val, Map<TVar<?>, Object> writes, List<TVar<?>> locked) {
            tryLock(tVar, locked);
            writes.put(tVar, val);
        }

        void commit(Map<TVar<?>, Long> reads, Map<TVar<?>, Object> writes, List<TVar<?>> locked) {
            try {
                validateReads(reads);
                apply(writes);
            } catch (ConcurrentModificationException e) {
                unlockAll(locked);
                throw e;
            }
            unlockAll(locked);
        }

        private void tryLock(TVar<?> tVar, List<TVar<?>> locked) {
            if (tVar.lock.isHeldByCurrentThread()) return;
            if (!tVar.trySteal()) {
                unlockAll(locked);
                throw new ConcurrentModificationException();
            }
            locked.add(tVar);
        }

        void unlockAll(List<TVar<?>> stolen) {
            stolen.forEach(TVar::unsteal);
            stolen.clear();
        }
    }
}
