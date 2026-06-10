package tech.provokedynamic.iastm.clock;

import java.util.concurrent.atomic.AtomicLong;

public enum TxClock {
    INSTANCE;

    private final AtomicLong version = new AtomicLong(0L);

    public long current() {
        return version.get();
    }

    public long next() {
        return version.incrementAndGet();
    }
}
