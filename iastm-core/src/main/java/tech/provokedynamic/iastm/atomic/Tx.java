package tech.provokedynamic.iastm.atomic;

import lombok.AccessLevel;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;

import java.util.*;

import static tech.provokedynamic.iastm.atomic.IASTM.__PROTOCOL;
import static tech.provokedynamic.iastm.atomic.Protocol.OPTIMISTIC;
import static tech.provokedynamic.iastm.atomic.Protocol.PESSIMISTIC_WRITE;

@EqualsAndHashCode(of = "rv")
@NoArgsConstructor(access = AccessLevel.PACKAGE)
public final class Tx implements Comparable<Tx> {

    static final Clock COMMIT_CLOCK = new Clock();

    final long rv = COMMIT_CLOCK.snapshot();

    private final Map<TVar<?>, Long> reads = new HashMap<>();
    private final TreeMap<TVar<?>, Object> writes = new TreeMap<>();

    private final List<TVar<?>> locked = new ArrayList<>();

    @SuppressWarnings("unchecked")
    <T> T read(TVar<T> tvar) {
        if (writes.containsKey(tvar)) {
            return (T) writes.get(tvar);
        }
        if (isPessimistic()) {
            return PESSIMISTIC_WRITE.INSTANCE.read(tvar, reads);
        }
        return OPTIMISTIC.INSTANCE.read(tvar, rv, reads);
    }

    <T> void write(TVar<T> tvar, T val) {
        if (isPessimistic()) {
            PESSIMISTIC_WRITE.INSTANCE.write(tvar, val, writes, locked);
        } else {
            writes.put(tvar, val);
        }
    }

    void commit() {
        if (writes.isEmpty()) return;
        if (isPessimistic()) {
            PESSIMISTIC_WRITE.INSTANCE.commit(reads, writes, locked);
        } else {
            OPTIMISTIC.INSTANCE.commit(reads, writes);
        }
    }

    private boolean isPessimistic() {
        return __PROTOCOL.get() instanceof PESSIMISTIC_WRITE;
    }

    @Override
    public int compareTo(Tx tx) {
        return Long.compare(this.rv, tx.rv);
    }
}
