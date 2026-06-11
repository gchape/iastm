package tech.provokedynamic.iastm.atomic;

import lombok.experimental.UtilityClass;
import lombok.extern.slf4j.Slf4j;
import tech.provokedynamic.iastm.TxMetrics;
import tech.provokedynamic.iastm.exception.MaxRetriesExceededException;
import tech.provokedynamic.iastm.exception.VersionEvictedException;
import tech.provokedynamic.iastm.retry.AdaptiveBackoff;

import java.util.ConcurrentModificationException;

/// Entry point for the Instrumented Adaptive STM runtime.
///
/// All transactional work is initiated through the two `start` overloads. The
/// runtime maintains three thread-local [ScopedValue]s that are invisible to
/// application code:
///
/// - `__TX`       — the active [Tx] for read/write set tracking
/// - `__STRATEGY` — the chosen [Strategy] for this attempt
/// - `__BACKOFF`  — the [AdaptiveBackoff] that survives across retries
///
/// Nested calls to `start` inherit the outer strategy without creating a new
/// backoff scope, achieving flat composition of atomic blocks.
@Slf4j
@UtilityClass
public class IASTM {

    /// The active transaction for the current thread. Bound inside [#run(Runnable)]
    /// for each attempt; never bound outside a `start` call.
    static final ScopedValue<Tx> __TX = ScopedValue.newInstance();

    /// The concurrency strategy selected for the current `start` scope.
    /// Inherited by nested `start` calls so inner blocks do not override the
    /// outer strategy.
    static final ScopedValue<Strategy> __STRATEGY = ScopedValue.newInstance();

    /// The backoff state that persists across retry attempts within a single
    /// top-level `start` invocation. Allows the delay to grow monotonically
    /// with the retry count rather than resetting on each attempt.
    private static final ScopedValue<AdaptiveBackoff> __BACKOFF = ScopedValue.newInstance();

    /// Maximum number of retry attempts before a [MaxRetriesExceededException] is thrown.
    private static final int MAX_RETRY = 512;

    /// Read ratio at or above which [Strategy#OPTIMISTIC] is selected.
    private static final double READ_HEAVY_THRESHOLD = 0.80;

    /// Write ratio at or above which [Strategy#PESSIMISTIC] is selected.
    private static final double WRITE_HEAVY_THRESHOLD = 0.60;

    /// Starts a transaction with automatic strategy selection.
    ///
    /// If called from within an existing transaction the outer strategy is
    /// inherited; otherwise [Strategy#OPTIMISTIC] is used as the default.
    /// Retries on [java.util.ConcurrentModificationException] or
    /// [VersionEvictedException] up to [#MAX_RETRY] times with [AdaptiveBackoff].
    ///
    /// @param body the transactional work to execute atomically
    /// @throws MaxRetriesExceededException if the transaction cannot commit within
    ///                                     the retry budget
    public static void start(Runnable body) {
        ScopedValue.where(__STRATEGY, __STRATEGY.isBound() ? __STRATEGY.get() : Strategy.OPTIMISTIC)
                .where(__BACKOFF, new AdaptiveBackoff())
                .run(() -> run(body));
    }

    /// Starts a transaction with strategy selected from historical metrics.
    ///
    /// The supplied [TxMetrics] snapshot is fed to [#selectStrategy(TxMetrics)]
    /// to pick between [Strategy#OPTIMISTIC] and [Strategy#PESSIMISTIC] before
    /// the first attempt. This overload is typically called by instrumented code
    /// that has observed the read/write ratio of previous executions.
    ///
    /// @param body    the transactional work to execute atomically
    /// @param metrics operation profile used to choose the concurrency strategy
    @SuppressWarnings("unused")
    public static void start(Runnable body, TxMetrics metrics) {
        ScopedValue.where(__STRATEGY, selectStrategy(metrics))
                .where(__BACKOFF, new AdaptiveBackoff())
                .run(() -> run(body));
    }

    /// Reads the current snapshot value of `tVar` within the active transaction.
    ///
    /// Delegates to [TVar#read(long)] using the transaction's `readPoint`.
    /// Must be called from within a `start` body.
    ///
    /// @param <T>  the value type of the variable
    /// @param tVar the transactional variable to read
    /// @return the value of `TVar` consistent with the transaction's read snapshot
    /// @throws IllegalStateException if called outside a transaction
    public static <T> T read(TVar<T> tVar) {
        __TX.orElseThrow(() -> new IllegalStateException("No active transaction"));
        return tVar.read(__TX.get().readPoint);
    }

    /// Stages write to `tVar` within the active transaction.
    ///
    /// Write is buffered in the transaction's write-set and only applied
    /// to the `TVar` if the transaction commits successfully.
    /// Must be called from within a `start` body.
    ///
    /// @param <T>   the value type of the variable
    /// @param tVar  the transactional variable to write
    /// @param value the new value to apply on commit
    /// @throws IllegalStateException if called outside a transaction
    public static <T> void write(TVar<T> tVar, T value) {
        __TX.orElseThrow(() -> new IllegalStateException("No active transaction"));
        tVar.write(value);
    }

    /// Core retry loop. Creates a fresh [Tx] on each attempt, runs `body` inside
    /// a new `__TX` scope, and commits. Aborts are caught here and forwarded to
    /// [#retry()].
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

    /// Advances the backoff state and parks the current thread for the computed
    /// delay. Throws [MaxRetriesExceededException] if the budget is exhausted.
    private static void retry() {
        AdaptiveBackoff backoff = __BACKOFF.get();
        if (backoff.getAttempt() >= MAX_RETRY) {
            throw new MaxRetriesExceededException(MAX_RETRY);
        }
        log.info("retry attempt={} strategy={}", backoff.getAttempt() + 1, __STRATEGY.get());
        backoff.park();
    }

    /// Selects a [Strategy] based on the read/write ratio encoded in `metrics`.
    ///
    /// - read ratio ≥ 80 % → [Strategy#OPTIMISTIC]
    /// - write ratio ≥ 60 % → [Strategy#PESSIMISTIC]
    /// - otherwise → [Strategy#OPTIMISTIC] (default)
    ///
    /// An empty metrics object (totalOps == 0) always yields [Strategy#OPTIMISTIC].
    ///
    /// @param metrics the operation profile of the transaction
    /// @return the strategy most likely to minimize contention for this workload
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

    /// The two concurrency strategies the runtime can apply to a transaction.
    public enum Strategy {
        /// Reads proceed without locking; conflicts are detected at commit time
        /// via read-set validation. Preferred for read-heavy workloads.
        OPTIMISTIC,

        /// Locks (`TVar`s) are stolen eagerly on first write, preventing any
        /// concurrent writer from proceeding. Preferred for write-heavy workloads
        /// to avoid repeated optimistic aborts.
        PESSIMISTIC
    }
}
