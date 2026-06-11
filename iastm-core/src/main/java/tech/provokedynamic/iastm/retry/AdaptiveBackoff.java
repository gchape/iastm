package tech.provokedynamic.iastm.retry;

import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.locks.LockSupport;

/// Three-phase adaptive backoff strategy for transaction retries.
///
/// Each call to [#park()] advances the internal attempt counter and selects a
/// delay according to the current phase:
///
/// 1. **Spin** (attempts 1–8) — calls [Thread#onSpinWait()] with no sleep,
///    favouring low-latency recovery under brief contention bursts.
/// 2. **Short exponential** (attempts 9–14) — exponential backoff from 1 ms,
///    capped at 16 ms, suited to moderate contention.
/// 3. **Long exponential** (attempts 15+) — exponential backoff from 2 ms,
///    capped at 256 ms, applied when the conflict is persistent.
///
/// A random jitter of up to [#JITTER_MS] milliseconds is added to every sleep
/// to reduce the chance of synchronised retry storms across threads.
///
/// A single `AdaptiveBackoff` instance is scoped to one top-level
/// [tech.provokedynamic.iastm.atomic.IASTM#start] invocation via
/// [java.lang.ScopedValue], so its counter accumulates across retries and the
/// delay grows correctly with each failed attempt.
@Slf4j
@NoArgsConstructor
public final class AdaptiveBackoff {

    /// Maximum random jitter added to every sleep interval, in milliseconds.
    private static final int JITTER_MS = 5;

    /// Number of initial spin-yield attempts before any sleeping begins.
    private static final int SPIN_THRESHOLD = 8;

    /// Attempt at which the backoff transitions from short to long exponential.
    private static final int SHORT_THRESHOLD = 14;

    /// Base sleep duration for the short exponential phase, in milliseconds.
    private static final long SHORT_BASE_MS = 1L;

    /// Upper bound on the short exponential sleep, in milliseconds.
    private static final long SHORT_MAX_MS = 16L;

    /// Base sleep duration for the long exponential phase, in milliseconds.
    private static final long LONG_BASE_MS = 2L;

    /// Upper bound on the long exponential sleep, in milliseconds.
    private static final long LONG_MAX_MS = 256L;

    /// Number of times [#park()] has been called on this instance.
    /// Exposed so [IASTM][tech.provokedynamic.iastm.atomic.IASTM] can compare
    /// it against `MAX_RETRY` before throwing [tech.provokedynamic.iastm.exception.MaxRetriesExceededException].
    @Getter
    private int attempt = 0;

    /// Delays the calling thread according to the current retry phase, then returns.
    ///
    /// Increments [#attempt] before selecting a delay, so the first call is
    /// attempt 1. Spin-phase calls return almost immediately; later calls park
    /// the thread for an exponentially growing interval plus random jitter.
    public void park() {
        attempt++;

        if (attempt <= SPIN_THRESHOLD) {
            log.debug("backoff attempt={} spin-yield", attempt);
            Thread.onSpinWait();
            return;
        }

        long sleepMs;
        if (attempt <= SHORT_THRESHOLD) {
            long exp = SHORT_BASE_MS * (1L << (attempt - SPIN_THRESHOLD - 1));
            sleepMs = Math.min(exp, SHORT_MAX_MS);
        } else {
            long exp = LONG_BASE_MS * (1L << Math.min(attempt - SHORT_THRESHOLD - 1, 7));
            sleepMs = Math.min(exp, LONG_MAX_MS);
        }

        sleepMs += ThreadLocalRandom.current().nextLong(JITTER_MS + 1);
        log.debug("backoff attempt={} sleeping={}ms", attempt, sleepMs);
        LockSupport.parkNanos(sleepMs * 1_000_000L);
    }
}