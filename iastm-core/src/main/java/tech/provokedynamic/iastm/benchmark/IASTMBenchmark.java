package tech.provokedynamic.iastm.benchmark;

import org.openjdk.jmh.annotations.*;
import org.openjdk.jmh.infra.Blackhole;
import tech.provokedynamic.iastm.TxMetrics;
import tech.provokedynamic.iastm.atomic.IASTM;
import tech.provokedynamic.iastm.atomic.TVar;

import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;

@BenchmarkMode(Mode.Throughput)
@OutputTimeUnit(TimeUnit.SECONDS)
@Warmup(iterations = 3, time = 1)
@Measurement(iterations = 5, time = 2)
@Fork(2)
@State(Scope.Benchmark)
@SuppressWarnings("unused")
public class IASTMBenchmark {

    private static final TxMetrics READ_HEAVY = new TxMetrics(9, 1);
    private static final TxMetrics WRITE_HEAVY = new TxMetrics(1, 9);

    private static final int ACCOUNT_COUNT = 64;

    private TVar<Integer> counter;
    private TVar<Integer>[] accounts;

    @SuppressWarnings("unchecked")
    @Setup(Level.Iteration)
    public void setup() {
        counter = new TVar<>(0);
        accounts = new TVar[ACCOUNT_COUNT];
        for (int i = 0; i < ACCOUNT_COUNT; i++) {
            accounts[i] = new TVar<>(100_000);
        }
    }

    @Benchmark
    @Threads(1)
    public void baseline_read_1t(Blackhole bh) {
        int[] v = new int[1];
        IASTM.start(() -> v[0] = IASTM.read(counter));
        bh.consume(v[0]);
    }

    @Benchmark
    @Threads(1)
    public void baseline_increment_1t() {
        IASTM.start(() -> IASTM.write(counter, IASTM.read(counter) + 1));
    }

    @Benchmark
    @Threads(4)
    public void bankTransfer_pessimistic_4t() {
        transfer(true);
    }

    @Benchmark
    @Threads(8)
    public void bankTransfer_pessimistic_8t() {
        transfer(true);
    }

    @Benchmark
    @Threads(4)
    public void multiRead_optimistic_4t(Blackhole bh) {
        multiRead(bh);
    }

    @Benchmark
    @Threads(8)
    public void multiRead_optimistic_8t(Blackhole bh) {
        multiRead(bh);
    }

    @Benchmark
    @Threads(4)
    public void bankTransfer_optimistic_4t() {
        transfer(false);
    }

    @Benchmark
    @Threads(8)
    public void bankTransfer_optimistic_8t() {
        transfer(false);
    }

    @Benchmark
    @Threads(4)
    public void mixed_readHeavy_4t(Blackhole bh) {
        mixed(bh);
    }

    @Benchmark
    @Threads(8)
    public void mixed_readHeavy_8t(Blackhole bh) {
        mixed(bh);
    }

    private void multiRead(Blackhole bh) {
        int idx = ThreadLocalRandom.current().nextInt(ACCOUNT_COUNT);
        TVar<Integer> a = accounts[idx];
        TVar<Integer> b = accounts[(idx + 1) % ACCOUNT_COUNT];
        int[] sum = new int[1];
        IASTM.start(() -> sum[0] = IASTM.read(a) + IASTM.read(b));
        bh.consume(sum[0]);
    }

    private void mixed(Blackhole bh) {
        int idx = ThreadLocalRandom.current().nextInt(ACCOUNT_COUNT);
        int[] out = new int[1];
        IASTM.start(() -> {
            int sum = 0;
            for (int i = 0; i < 8; i++) {
                sum += IASTM.read(accounts[(idx + i) % ACCOUNT_COUNT]);
            }
            TVar<Integer> sink = accounts[(idx + ACCOUNT_COUNT / 2) % ACCOUNT_COUNT];
            IASTM.write(sink, IASTM.read(sink) + 1);
            out[0] = sum;
        }, READ_HEAVY);
        bh.consume(out[0]);
    }

    private void transfer(boolean pessimistic) {
        ThreadLocalRandom rng = ThreadLocalRandom.current();
        int from = rng.nextInt(ACCOUNT_COUNT);
        int to = (from + 1 + rng.nextInt(ACCOUNT_COUNT - 1)) % ACCOUNT_COUNT;
        TVar<Integer> src = accounts[from];
        TVar<Integer> dst = accounts[to];
        Runnable body = () -> {
            int v = IASTM.read(src);
            if (v > 0) {
                IASTM.write(src, v - 1);
                IASTM.write(dst, IASTM.read(dst) + 1);
            }
        };
        if (pessimistic) IASTM.start(body, WRITE_HEAVY);
        else IASTM.start(body);
    }
}
