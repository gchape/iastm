package tech.provokedynamic.iastm.atomic;

import lombok.experimental.UtilityClass;
import lombok.extern.slf4j.Slf4j;
import tech.provokedynamic.iastm.TxMetrics;
import tech.provokedynamic.iastm.exception.MaxRetriesExceededException;
import tech.provokedynamic.iastm.exception.VersionEvictedException;
import tech.provokedynamic.iastm.retry.AdaptiveBackoff;

import java.util.ConcurrentModificationException;

@Slf4j
@UtilityClass
public class IASTM {

    static final ScopedValue<Tx> __TX = ScopedValue.newInstance();
    static final ScopedValue<Protocol> __PROTOCOL = ScopedValue.newInstance();
    private static final ScopedValue<AdaptiveBackoff> __BACKOFF = ScopedValue.newInstance();

    private static final int MAX_RETRY = 512;
    private static final double READ_HEAVY_THRESHOLD = 0.8;
    private static final double WRITE_HEAVY_THRESHOLD = 0.6;

    public static void start(Runnable body) {
        ScopedValue.where(__PROTOCOL, __PROTOCOL.isBound() ? __PROTOCOL.get() : Protocol.OPTIMISTIC.INSTANCE)
                .where(__BACKOFF, new AdaptiveBackoff())
                .run(() -> run(body));
    }

    @SuppressWarnings("unused")
    public static void start(Runnable body, TxMetrics metrics) {
        ScopedValue.where(__PROTOCOL, selectStrategy(metrics))
                .where(__BACKOFF, new AdaptiveBackoff())
                .run(() -> run(body));
    }

    public static <T> T read(TVar<T> tVar) {
        return __TX.orElseThrow(() -> new IllegalStateException("No active transaction"))
                .read(tVar);
    }

    public static <T> void write(TVar<T> tVar, T val) {
        __TX.orElseThrow(() -> new IllegalStateException("No active transaction"))
                .write(tVar, val);
    }

    private static void run(Runnable body) {
        while (true) {
            try {
                Tx tx = new Tx();
                ScopedValue.where(__TX, tx).run(body);
                tx.commit();
                return;
            } catch (ConcurrentModificationException | VersionEvictedException e) {
                retry();
            }
        }
    }

    private static void retry() {
        AdaptiveBackoff backoff = __BACKOFF.get();
        if (backoff.getAttempt() >= MAX_RETRY) {
            throw new MaxRetriesExceededException(MAX_RETRY);
        }
        backoff.park();
    }

    private static Protocol selectStrategy(TxMetrics metrics) {
        if (metrics.totalOps() == 0) {
            return Protocol.OPTIMISTIC.INSTANCE;
        }
        double readRatio = (double) metrics.readOps() / metrics.totalOps();
        double writeRatio = (double) metrics.writeOps() / metrics.totalOps();
        if (readRatio >= READ_HEAVY_THRESHOLD) {
            return Protocol.OPTIMISTIC.INSTANCE;
        }
        if (writeRatio >= WRITE_HEAVY_THRESHOLD) {
            return Protocol.PESSIMISTIC_WRITE.INSTANCE;
        }
        return Protocol.OPTIMISTIC.INSTANCE;
    }
}
