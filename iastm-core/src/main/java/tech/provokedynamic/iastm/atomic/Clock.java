package tech.provokedynamic.iastm.atomic;

import java.lang.invoke.MethodHandles;
import java.lang.invoke.VarHandle;

final class Clock {

    private static final VarHandle COUNTER;

    static {
        try {
            COUNTER = MethodHandles.lookup().findVarHandle(Clock.class, "counter", long.class);
        } catch (ReflectiveOperationException e) {
            throw new ExceptionInInitializerError(e);
        }
    }

    @SuppressWarnings({"FieldMayBeFinal", "unused"})
    private volatile long counter = 0L;

    long snapshot() {
        return counter;
    }

    long advance() {
        return (long) COUNTER.getAndAdd(this, 1L) + 1L;
    }
}
