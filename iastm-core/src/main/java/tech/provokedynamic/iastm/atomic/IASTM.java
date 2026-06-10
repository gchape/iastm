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
    static final ScopedValue<Strategy> __STRATEGY = ScopedValue.newInstance();

    private static final ScopedValue<AdaptiveBackoff> __BACKOFF = ScopedValue.newInstance();

    private static final int MAX_RETRY = 512;
    private static final double READ_HEAVY_THRESHOLD = 0.80;
    private static final double WRITE_HEAVY_THRESHOLD = 0.60;

    /// Starts a transaction with automatically-selected strategy.
    /// The agent upgrades this call site to [#start(Runnable, TxMetrics)] at load time.
    public static void start(Runnable body) {
        Strategy inherited = __STRATEGY.isBound() ? __STRATEGY.get() : Strategy.OPTIMISTIC;
        ScopedValue.where(__STRATEGY, inherited)
                .where(__BACKOFF, new AdaptiveBackoff())
                .run(() -> run(body));
    }

    /// Starts a transaction using the strategy selected from the supplied metrics.
    /// This overload is the target for agent-rewritten call sites.
    @SuppressWarnings("unused")
    public static void start(Runnable body, TxMetrics metrics) {
        ScopedValue.where(__STRATEGY, selectStrategy(metrics))
                .where(__BACKOFF, new AdaptiveBackoff())
                .run(() -> run(body));
    }

    /// Reads the current value of `tVar` within the active transaction.
    ///
    /// @throws IllegalStateException if called outside a transaction
    public static <T> T read(TVar<T> tVar) {
        __TX.orElseThrow(() -> new IllegalStateException("No active transaction"));
        return tVar.read(__TX.get().readPoint);
    }

    /// Writes `value` to `tVar` within the active transaction.
    ///
    /// @throws IllegalStateException if called outside a transaction
    public static <T> void write(TVar<T> tVar, T value) {
        __TX.orElseThrow(() -> new IllegalStateException("No active transaction"));
        tVar.write(value);
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
        if (metrics.totalOps() == 0) return Strategy.OPTIMISTIC;
        double readRatio = (double) metrics.readOps() / metrics.totalOps();
        double writeRatio = (double) metrics.writeOps() / metrics.totalOps();
        if (readRatio >= READ_HEAVY_THRESHOLD) return Strategy.OPTIMISTIC;
        if (writeRatio >= WRITE_HEAVY_THRESHOLD) return Strategy.PESSIMISTIC;
        return Strategy.OPTIMISTIC;
    }

    public enum Strategy {OPTIMISTIC, PESSIMISTIC}
}
