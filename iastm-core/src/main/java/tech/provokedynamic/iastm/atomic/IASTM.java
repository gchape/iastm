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

    /// Active transaction for the current thread; unbound outside a `start` call.
    static final ScopedValue<Tx> __TX = ScopedValue.newInstance();

    /// Strategy for the current `start` scope; inherited by nested calls.
    static final ScopedValue<Strategy> __STRATEGY = ScopedValue.newInstance();

    /// Backoff state shared across retries so delay grows monotonically.
    private static final ScopedValue<AdaptiveBackoff> __BACKOFF = ScopedValue.newInstance();

    /// Maximum retry attempts before [MaxRetriesExceededException] is thrown.
    private static final int MAX_RETRY = 512;

    /// Read ratio threshold for selecting [Strategy#OPTIMISTIC].
    private static final double READ_HEAVY_THRESHOLD = 0.80;

    /// Write ratio threshold for selecting [Strategy#PESSIMISTIC].
    private static final double WRITE_HEAVY_THRESHOLD = 0.60;

    public static void start(Runnable body) {
        ScopedValue.where(__STRATEGY, __STRATEGY.isBound() ? __STRATEGY.get() : Strategy.OPTIMISTIC)
                .where(__BACKOFF, new AdaptiveBackoff())
                .run(() -> run(body));
    }

    @SuppressWarnings("unused")
    public static void start(Runnable body, TxMetrics metrics) {
        ScopedValue.where(__STRATEGY, selectStrategy(metrics))
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
                log.debug("tx start readPoint={} strategy={}", tx.readPoint, __STRATEGY.get());
                ScopedValue.where(__TX, tx).run(body);
                tx.commit();
                log.debug("tx committed readPoint={}", tx.readPoint);
                return;
            } catch (ConcurrentModificationException | VersionEvictedException e) {
                log.debug("tx conflict — retrying ({})", e.getClass().getSimpleName());
                retry();
            }
        }
    }

    private static void retry() {
        AdaptiveBackoff backoff = __BACKOFF.get();
        if (backoff.getAttempt() >= MAX_RETRY) {
            throw new MaxRetriesExceededException(MAX_RETRY);
        }
        log.info("retry attempt={} strategy={}", backoff.getAttempt() + 1, __STRATEGY.get());
        backoff.park();
    }

    private static Strategy selectStrategy(TxMetrics metrics) {
        if (metrics.totalOps() == 0) {
            return Strategy.OPTIMISTIC;
        }
        double readRatio = (double) metrics.readOps() / metrics.totalOps();
        double writeRatio = (double) metrics.writeOps() / metrics.totalOps();
        if (readRatio >= READ_HEAVY_THRESHOLD) {
            return Strategy.OPTIMISTIC;
        }
        if (writeRatio >= WRITE_HEAVY_THRESHOLD) {
            return Strategy.PESSIMISTIC;
        }
        return Strategy.OPTIMISTIC;
    }

    /// Concurrency strategies available to a transaction.
    public enum Strategy {
        /// Lock-free reads; conflicts detected at commit via read-set validation.
        /// Preferred for read-heavy workloads.
        OPTIMISTIC,

        /// Locks stolen eagerly on first write; prevents concurrent writers.
        /// Preferred for write-heavy workloads to avoid repeated optimistic aborts.
        PESSIMISTIC
    }
}
