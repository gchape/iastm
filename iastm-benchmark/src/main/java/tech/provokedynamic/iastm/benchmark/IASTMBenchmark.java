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

    /// Metrics hint representing a read-heavy workload (90 % reads, 10 % writes).
    /// Passed to [IASTM#start(Runnable, TxMetrics)] to force [IASTM.Strategy#OPTIMISTIC].
    private static final TxMetrics READ_HEAVY = new TxMetrics(9, 1);

    /// Metrics hint representing a write-heavy workload (10 % reads, 90 % writes).
    /// Passed to [IASTM#start(Runnable, TxMetrics)] to force [IASTM.Strategy#PESSIMISTIC].
    private static final TxMetrics WRITE_HEAVY = new TxMetrics(1, 9);

    /// Number of simulated bank accounts. A power-of-two-friendly value that
    /// provides enough variables to spread contention across threads.
    private static final int ACCOUNT_COUNT = 64;

    /// Shared counter used by single-threaded baseline benchmarks.
    private TVar<Integer> counter;

    /// Array of account `TVar`s used by transfer and multi-read benchmarks.
    private TVar<Integer>[] accounts;

    /// Reinitialize all shared state before each measurement iteration to prevent
    /// counter overflow or balance depletion from affecting timing results.
    @SuppressWarnings("unchecked")
    @Setup(Level.Iteration)
    public void setup() {
        counter = new TVar<>(0);
        accounts = new TVar[ACCOUNT_COUNT];
        for (int i = 0; i < ACCOUNT_COUNT; i++) {
            accounts[i] = new TVar<>(100_000);
        }
    }

    /// Baseline: single-threaded read of a single `TVar` with no contention.
    /// Establishes the minimum per-transaction overhead.
    @Benchmark
    @Threads(1)
    public void baseline_read_1t(Blackhole bh) {
        int[] v = new int[1];
        IASTM.start(() -> v[0] = IASTM.read(counter));
        bh.consume(v[0]);
    }

    /// Baseline: single-threaded read-modify-write of a single `TVar`.
    /// Isolates commit cost without any inter-thread conflict.
    @Benchmark
    @Threads(1)
    public void baseline_increment_1t() {
        IASTM.start(() -> IASTM.write(counter, IASTM.read(counter) + 1));
    }

    /// Pessimistic bank transfer under 4-thread contention.
    @Benchmark
    @Threads(4)
    public void bankTransfer_pessimistic_4t() {
        transfer(true);
    }

    /// Pessimistic bank transfer under 8-thread contention.
    @Benchmark
    @Threads(8)
    public void bankTransfer_pessimistic_8t() {
        transfer(true);
    }

    /// Optimistic multi-read under 4-thread contention.
    @Benchmark
    @Threads(4)
    public void multiRead_optimistic_4t(Blackhole bh) {
        multiRead(bh);
    }

    /// Optimistic multi-read under 8-thread contention.
    @Benchmark
    @Threads(8)
    public void multiRead_optimistic_8t(Blackhole bh) {
        multiRead(bh);
    }

    /// Optimistic bank transfer under 4-thread contention.
    @Benchmark
    @Threads(4)
    public void bankTransfer_optimistic_4t() {
        transfer(false);
    }

    /// Optimistic bank transfer under 8-thread contention.
    @Benchmark
    @Threads(8)
    public void bankTransfer_optimistic_8t() {
        transfer(false);
    }

    /// Mixed read-heavy workload (8 reads + 1 write) under 4-thread contention.
    /// Uses an explicit [#READ_HEAVY] metrics hint to select the optimistic strategy.
    @Benchmark
    @Threads(4)
    public void mixed_readHeavy_4t(Blackhole bh) {
        mixed(bh);
    }

    /// Mixed read-heavy workload (8 reads + 1 write) under 8-thread contention.
    @Benchmark
    @Threads(8)
    public void mixed_readHeavy_8t(Blackhole bh) {
        mixed(bh);
    }

    /// Reads two adjacent accounts and sums their balances in a single snapshot
    /// transaction. The pair is chosen randomly to distribute read-set overlap
    /// across threads.
    private void multiRead(Blackhole bh) {
        int idx = ThreadLocalRandom.current().nextInt(ACCOUNT_COUNT);
        TVar<Integer> a = accounts[idx];
        TVar<Integer> b = accounts[(idx + 1) % ACCOUNT_COUNT];
        int[] sum = new int[1];
        IASTM.start(() -> sum[0] = IASTM.read(a) + IASTM.read(b));
        bh.consume(sum[0]);
    }

    /// Reads 8 consecutive accounts, then increments a single "sink" account on
    /// the opposite side of the array. Passes [#READ_HEAVY] to bias strategy
    /// selection towards optimistic.
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

    /// Transfers 1 unit from a randomly chosen source account to a distinct
    /// destination account. The transfer is skipped if the source balance is zero
    /// to prevent negative balances. Strategy is selected via `pessimistic` flag.
    ///
    /// @param pessimistic if `true`, passes [#WRITE_HEAVY] metrics to force
    ///                    [IASTM.Strategy#PESSIMISTIC]; otherwise uses the default
    ///                    optimistic strategy
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
