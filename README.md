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

| Module | Description |
|---|---|
| `iastm-core` | `IASTM`, `TVar`, `Tx`, clocks, backoff, exceptions, JMH benchmarks |
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
IASTM.start(() -> IASTM.write(counter, IASTM.read(counter) + 1));
```

### Custom scan prefix

```bash
-javaagent:iastm-agent.jar=com.example.myapp,com.example.other
```

## Running benchmarks

```bash
java --enable-preview -jar iastm-core/target/benchmarks.jar
```

## Strategy selection

| Condition | Strategy |
|---|---|
| readOps / totalOps ≥ 80 % | OPTIMISTIC |
| writeOps / totalOps ≥ 60 % | PESSIMISTIC |
| Otherwise | OPTIMISTIC |

## Key design points

- **ScopedValues** (JDK 21+) instead of `ThreadLocal` — virtual-thread friendly
- **MVCC history** in `TVar` — up to 32 versions, enables snapshot reads
- **Ordered write-lock acquisition** by `TVar.id` — prevents deadlocks
- **Adaptive backoff** — spin → short exponential → long exponential with jitter
- **Java Class-File API** (JDK 24+) used in the transformer — no ASM dependency needed
