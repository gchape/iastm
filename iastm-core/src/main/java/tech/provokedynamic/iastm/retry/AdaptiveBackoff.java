package tech.provokedynamic.iastm.retry;

import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.locks.LockSupport;

@Slf4j
@NoArgsConstructor
public final class AdaptiveBackoff {

    /// Maximum jitter added to each sleep to desynchronize concurrent retriers.
    private static final int JITTER_MS = 5;

    /// Attempts up to and including this value use [Thread#onSpinWait()] — no parking.
    private static final int SPIN_THRESHOLD = 8;

    /// Attempts above [#SPIN_THRESHOLD] and up to this value use the short exponential ladder.
    private static final int SHORT_THRESHOLD = 14;

    /// Starting sleep for the short ladder (attempts 9–14).
    private static final long SHORT_BASE_MS = 1L;

    /// Ceiling for the short ladder; sleeps are capped here before jitter is added.
    private static final long SHORT_MAX_MS = 16L;

    /// Starting sleep for the long ladder (attempts 15+).
    private static final long LONG_BASE_MS = 2L;

    /// Ceiling for the long ladder; sleeps are capped here before jitter is added.
    private static final long LONG_MAX_MS = 256L;

    /// Number of [#park()] calls so far; checked against `MAX_RETRY` in [IASTM].
    @Getter
    private int attempt = 0;

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
