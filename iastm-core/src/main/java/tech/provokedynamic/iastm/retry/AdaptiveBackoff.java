package tech.provokedynamic.iastm.retry;

import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.locks.LockSupport;

@Slf4j
@NoArgsConstructor
public final class AdaptiveBackoff {

    private static final int JITTER_MS = 5;

    private static final int SPIN_THRESHOLD = 8;

    private static final int SHORT_THRESHOLD = 14;
    private static final long SHORT_BASE_MS = 1L;
    private static final long SHORT_MAX_MS = 16L;

    private static final long LONG_BASE_MS = 2L;
    private static final long LONG_MAX_MS = 256L;

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
