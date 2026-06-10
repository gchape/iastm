package tech.provokedynamic.iastm.clock;

import java.util.concurrent.atomic.AtomicLong;

public enum TVarClock {
    INSTANCE;

    private final AtomicLong version = new AtomicLong(0L);

    public long next() {
        return version.incrementAndGet();
    }
}
