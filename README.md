# IASTM — Instrumented Adaptive Software Transactional Memory

A JVM-native STM library for Java 25 that uses a **bytecode-instrumentation agent** to
automatically inject read/write metrics at load time, enabling **adaptive
optimistic/pessimistic concurrency strategy selection** at runtime — with zero boilerplate
in application code.

## How it works

```
Your code                       After agent transform
────────────────────────────    ──────────────────────────────────────────────
IASTM.start(() -> {         →   IASTM.start(() -> {
    int v = IASTM.read(x);          int v = IASTM.read(x);       // readOps=1
    IASTM.write(x, v + 1);          IASTM.write(x, v + 1);       // writeOps=1
});                             }, new TxMetrics(1, 1));          // injected ✓
```

The agent counts `IASTM.read` / `IASTM.write` calls in the lambda body at class-load time
and rewrites the bare `start(Runnable)` call to `start(Runnable, TxMetrics)`.
`IASTM` then picks **OPTIMISTIC** (≥80 % reads) or **PESSIMISTIC** (≥60 % writes)
automatically.

## Modules

| Module        | Description                                                          |
|---------------|----------------------------------------------------------------------|
| `iastm-core`  | `IASTM`, `TVar`, `Tx`, clocks, backoff, exceptions, JMH benchmarks   |
| `iastm-agent` | Java agent + bytecode transformer (`IASTMAgent`, `IASTMTransformer`) |

## Requirements

- JDK 25 (preview features enabled)
- Maven 3.9+

## Build

```bash
mvn clean package
```

This produces:

- `iastm-agent/target/iastm-agent-1.0-SNAPSHOT.jar` — shaded agent JAR
- `iastm-core/target/benchmarks.jar` — fat JAR for JMH

## Usage

### Static attach (recommended)

```bash
java --enable-preview \
     -javaagent:iastm-agent/target/iastm-agent-1.0-SNAPSHOT.jar \
     -jar your-app.jar
```

### Programmatic attach (tests)

```java
IASTMAgent.attach(null); // instrument default prefix

TVar<Integer> counter = new TVar<>(0);
IASTM.

start(() ->IASTM.

write(counter, IASTM.read(counter) +1));
```

### Custom scan prefix

```bash
-javaagent:iastm-agent.jar=com.example.myapp,com.example.other
```

## Running benchmarks

```bash
java --enable-preview -jar iastm-core/target/benchmarks.jar
```

## Benchmarks

JDK 25.0.3, OpenJDK 64-Bit Server VM, AMD Ryzen 5 7535HS (12 threads) @ 4.60 GHz.
Throughput in ops/s, higher is better.

| Benchmark                     | Threads | ops/s      | Error       |
|-------------------------------|---------|------------|-------------|
| `baseline_read_1t`            | 1       | 11,103,518 | ± 223,830   |
| `baseline_increment_1t`       | 1       | 7,147,136  | ± 253,218   |
| `multiRead_optimistic_4t`     | 4       | 24,775,405 | ± 1,773,964 |
| `multiRead_optimistic_8t`     | 8       | 31,252,384 | ± 3,842,396 |
| `bankTransfer_optimistic_4t`  | 4       | 4,281,955  | ± 156,278   |
| `bankTransfer_optimistic_8t`  | 8       | 3,785,275  | ± 56,655    |
| `bankTransfer_pessimistic_4t` | 4       | 6,456,801  | ± 348,298   |
| `bankTransfer_pessimistic_8t` | 8       | 6,423,554  | ± 690,126   |
| `mixed_readHeavy_4t`          | 4       | 4,288,779  | ± 280,226   |
| `mixed_readHeavy_8t`          | 8       | 4,573,934  | ± 219,983   |

`multiRead` scales past 8 threads because reads are non-contending MVCC snapshots — each thread scans its own
`readPoint` without acquiring any lock. `bankTransfer` throughput drops under optimistic 8t due to write conflicts and
retry overhead; pessimistic holds steady by acquiring locks eagerly and avoiding wasted work.

## Strategy selection

| Condition                  | Strategy    |
|----------------------------|-------------|
| readOps / totalOps ≥ 80 %  | OPTIMISTIC  |
| writeOps / totalOps ≥ 60 % | PESSIMISTIC |
| Otherwise                  | OPTIMISTIC  |

## Key design points

- **ScopedValues** (JDK 21+) instead of `ThreadLocal` — virtual-thread friendly
- **MVCC history** in `TVar` — 32-slot pre-allocated ring buffer with `VarHandle` acquire/release ordering, zero
  allocation on append after construction
- **Ordered write-lock acquisition** by `TVar.id` — prevents deadlocks
- **Adaptive backoff** — spin → short exponential → long exponential with jitter
- **Java Class-File API** (JDK 24+) used in the transformer — no ASM dependency needed