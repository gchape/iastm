package tech.provokedynamic.iastm.clock;

import java.util.concurrent.atomic.AtomicLong;

public enum TxClock {
    INSTANCE;
    private final AtomicLong global = new AtomicLong(0L);

    public long current() {
        return global.get();
    }

    public long next() {
        return global.incrementAndGet();
    }
}
