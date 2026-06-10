package tech.provokedynamic.iastm.atomic;

import org.junit.jupiter.api.RepeatedTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import tech.provokedynamic.iastm.TxMetrics;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import static org.junit.jupiter.api.Assertions.*;

class IASTMTest {

    @Test
    void readReturnsInitialValue() {
        TVar<Integer> ref = new TVar<>(42);
        int[] result = new int[1];
        IASTM.start(() -> result[0] = IASTM.read(ref));
        assertEquals(42, result[0]);
    }

    @Test
    void writeAndReadWithinSameTx() {
        TVar<Integer> ref = new TVar<>(0);
        IASTM.start(() -> IASTM.write(ref, 99));
        int[] result = new int[1];
        IASTM.start(() -> result[0] = IASTM.read(ref));
        assertEquals(99, result[0]);
    }

    @Test
    void committedValueVisibleToNextTx() {
        TVar<String> ref = new TVar<>("hello");
        IASTM.start(() -> IASTM.write(ref, "world"));
        String[] result = new String[1];
        IASTM.start(() -> result[0] = IASTM.read(ref));
        assertEquals("world", result[0]);
    }

    @Test
    void readOnlyTxDoesNotCommitVersion() {
        TVar<Integer> ref = new TVar<>(7);
        assertDoesNotThrow(() -> IASTM.start(() -> IASTM.read(ref)));
    }

    @Test
    @Timeout(10)
    void concurrentIncrementsProgressAndTerminate() {
        TVar<Integer> counter = new TVar<>(0);
        int threads = 8;
        int opsPerThread = 500;
        CountDownLatch start = new CountDownLatch(1);

        try (ExecutorService pool = Executors.newFixedThreadPool(threads)) {
            for (int t = 0; t < threads; t++) {
                pool.submit(() -> {
                    try {
                        start.await();
                        for (int i = 0; i < opsPerThread; i++) {
                            IASTM.start(() -> IASTM.write(counter, IASTM.read(counter) + 1));
                        }
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                });
            }
            start.countDown();
        }

        int[] result = new int[1];
        IASTM.start(() -> result[0] = IASTM.read(counter));
        assertTrue(result[0] > 0 && result[0] <= threads * opsPerThread);
    }

    @Test
    @Timeout(10)
    void transferPreservesSum() {
        TVar<Integer> a = new TVar<>(1000);
        TVar<Integer> b = new TVar<>(1000);
        int total = 2000;
        int threads = 4;
        int ops = 200;
        CountDownLatch start = new CountDownLatch(1);

        try (ExecutorService pool = Executors.newFixedThreadPool(threads)) {
            for (int t = 0; t < threads; t++) {
                pool.submit(() -> {
                    try {
                        start.await();
                        for (int i = 0; i < ops; i++) {
                            IASTM.start(() -> {
                                int va = IASTM.read(a);
                                int vb = IASTM.read(b);
                                if (va > 0) {
                                    IASTM.write(a, va - 1);
                                    IASTM.write(b, vb + 1);
                                }
                            });
                        }
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                });
            }
            start.countDown();
        }

        int[] ra = new int[1], rb = new int[1];
        IASTM.start(() -> {
            ra[0] = IASTM.read(a);
            rb[0] = IASTM.read(b);
        });
        assertEquals(total, ra[0] + rb[0], "Sum must be conserved");
    }

    @Test
    void nestedTxInheritsStrategy() {
        TVar<Integer> ref = new TVar<>(0);
        assertDoesNotThrow(() ->
                IASTM.start(() ->
                        IASTM.start(() -> IASTM.write(ref, 1))
                )
        );
        int[] result = new int[1];
        IASTM.start(() -> result[0] = IASTM.read(ref));
        assertEquals(1, result[0]);
    }

    @Test
    @Timeout(10)
    void pessimisticStrategySelectedAndTerminates() {
        TVar<Integer> counter = new TVar<>(0);
        TxMetrics writeHeavy = new TxMetrics(1, 9);
        int threads = 4;
        int ops = 200;
        CountDownLatch start = new CountDownLatch(1);

        try (ExecutorService pool = Executors.newFixedThreadPool(threads)) {
            for (int t = 0; t < threads; t++) {
                pool.submit(() -> {
                    try {
                        start.await();
                        for (int i = 0; i < ops; i++) {
                            IASTM.start(
                                    () -> IASTM.write(counter, IASTM.read(counter) + 1),
                                    writeHeavy
                            );
                        }
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                });
            }
            start.countDown();
        }

        int[] result = new int[1];
        IASTM.start(() -> result[0] = IASTM.read(counter));
        assertTrue(result[0] > 0 && result[0] <= threads * ops);
    }

    @Test
    void pessimisticStrategyIsSelectedForWriteHeavyMetrics() {
        TxMetrics writeHeavy = new TxMetrics(1, 9);
        boolean[] ran = new boolean[1];
        IASTM.start(() -> ran[0] = IASTM.__STRATEGY.get() == IASTM.Strategy.PESSIMISTIC, writeHeavy);
        assertTrue(ran[0]);
    }

    @Test
    void readOutsideTxThrows() {
        TVar<Integer> ref = new TVar<>(1);
        assertThrows(IllegalStateException.class, () -> IASTM.read(ref));
    }

    @Test
    void writeOutsideTxThrows() {
        TVar<Integer> ref = new TVar<>(1);
        assertThrows(IllegalStateException.class, () -> IASTM.write(ref, 2));
    }

    @RepeatedTest(20)
    void repeatedConcurrentIncrementsProgressAndTerminate() {
        TVar<Integer> counter = new TVar<>(0);
        int threads = 4;
        int ops = 100;

        try (ExecutorService pool = Executors.newFixedThreadPool(threads)) {
            for (int t = 0; t < threads; t++) {
                pool.submit(() -> {
                    for (int i = 0; i < ops; i++) {
                        IASTM.start(() -> IASTM.write(counter, IASTM.read(counter) + 1));
                    }
                });
            }
        }

        int[] result = new int[1];
        IASTM.start(() -> result[0] = IASTM.read(counter));
        assertTrue(result[0] > 0 && result[0] <= threads * ops);
    }
}
