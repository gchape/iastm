# IASTM — Instrumented Adaptive Software Transactional Memory

<img width="300" height="115" alt="iastm_logo_v15" src="https://github.com/user-attachments/assets/b63689c6-c583-498f-bd15-193c888f4afa" />

A JVM-native STM library for Java 25 that uses a **bytecode-instrumentation agent** to
automatically inject read/write metrics at class-load time, enabling **adaptive
optimistic/pessimistic concurrency strategy selection** at runtime — with zero boilerplate
in application code.

---

## Table of contents

- [How it works](#how-it-works)
- [Architecture](#architecture)
- [Core components](#core-components)
    - [IASTM](#iastm)
    - [TVar](#tvar)
    - [Tx](#tx)
    - [RingBufferHistory](#ringbufferhistory)
    - [TxClock / TVarClock](#txclock--tvarclock)
    - [AdaptiveBackoff](#adaptivebackoff)
    - [IASTMAgent / IASTMTransformer](#iastmagent--iastmtransformer)
- [Concurrency strategies](#concurrency-strategies)
- [MVCC and snapshot isolation](#mvcc-and-snapshot-isolation)
- [Deadlock prevention](#deadlock-prevention)
- [Error handling](#error-handling)
- [Modules](#modules)
- [Requirements](#requirements)
- [Running benchmarks](#running-benchmarks)
- [Benchmarks](#benchmarks)

---

## How it works

```
Your code                        After agent transform
─────────────────────────────    ──────────────────────────────────────────────
IASTM.start(() -> {          →   IASTM.start(() -> {
    int v = IASTM.read(x);           int v = IASTM.read(x);       // readOps=1
    IASTM.write(x, v + 1);           IASTM.write(x, v + 1);       // writeOps=1
});                              }, new TxMetrics(1, 1));          // injected ✓
```

At class-load time the agent performs a two-pass static analysis of each lambda body
passed to `IASTM.start`:

1. **Count collection** — counts `IASTM.read` and `IASTM.write` call sites in every
   method and synthetic lambda, building a call graph of lambda children.
2. **Count resolution** — propagates counts transitively through nested lambdas so
   that each `start` site receives the combined read/write total of its entire closure
   tree.
3. **Rewrite** — replaces the bare `start(Runnable)` call with a sequence that
   constructs a `TxMetrics` and calls `start(Runnable, TxMetrics)`.

`IASTM` then selects **OPTIMISTIC** or **PESSIMISTIC** strategy before the first
retry attempt, with no runtime overhead in the hot path.

---

## Architecture

```
┌─────────────────────────────────────────────────────────┐
│                     Application code                    │
│   IASTM.start(() -> { IASTM.read(x); IASTM.write(y) }) │
└───────────────────┬─────────────────────────────────────┘
                    │ rewritten at class-load by IASTMAgent
                    ▼
┌─────────────────────────────────────────────────────────┐
│  IASTM.start(body, TxMetrics(readOps, writeOps))        │
│                                                         │
│  selectStrategy()                                       │
│  ┌─────────────────┐       ┌──────────────────────┐     │
│  │   OPTIMISTIC    │       │    PESSIMISTIC        │     │
│  │                 │       │                       │     │
│  │ read: MVCC snap │       │ read: tvar.value      │     │
│  │ write: buffered │       │ write: steal lock     │     │
│  │ commit: lock+   │       │ commit: validate+     │     │
│  │  validate+apply │       │  apply, unlock        │     │
│  └────────┬────────┘       └──────────┬────────────┘     │
│           └──────────┬───────────────┘                   │
│                      ▼                                   │
│            ConcurrentModificationException               │
│            VersionEvictedException                       │
│                      │                                   │
│            AdaptiveBackoff.park()  ──► retry (≤512)      │
└─────────────────────────────────────────────────────────┘
                    │ commits via
                    ▼
┌─────────────────────────────────────────────────────────┐
│  TVar  ──  RingBufferHistory (32-slot ring, VarHandle)  │
│            TxClock (global logical clock)               │
└─────────────────────────────────────────────────────────┘
```

---

## Core components

### IASTM

`tech.provokedynamic.iastm.atomic.IASTM` is the public API surface. All state is
propagated via `ScopedValue` (JDK 21+), making the library virtual-thread safe with
no `ThreadLocal` leaks.

| ScopedValue  | Type              | Purpose                                             |
|--------------|-------------------|-----------------------------------------------------|
| `__TX`       | `Tx`              | Active transaction for the current thread scope     |
| `__STRATEGY` | `Strategy`        | Selected concurrency strategy; inherited by nesting |
| `__BACKOFF`  | `AdaptiveBackoff` | Retry state shared across all attempts in one start |

**Strategy selection** is performed once per `start(Runnable, TxMetrics)` call using
the injected metrics:

| Condition                    | Strategy      |
|------------------------------|---------------|
| `readOps / totalOps ≥ 80 %`  | `OPTIMISTIC`  |
| `writeOps / totalOps ≥ 60 %` | `PESSIMISTIC` |
| Otherwise / no metrics       | `OPTIMISTIC`  |

Nested `start` calls inherit the outer strategy rather than re-selecting, preventing
strategy thrashing in recursive transactions.

### TVar

`TVar<T>` is the transactional variable. Each instance carries:

- **`id`** — globally unique, monotonically increasing long assigned via `TVarClock` at
  construction. Used as the sole key for lock-acquisition ordering.
- **`value`** — the current committed value; `volatile` for visibility without a lock in
  pessimistic reads.
- **`version`** — the `TxClock` stamp of the last committed write; `volatile`.
- **`history`** — a `RingBufferHistory<T>` retaining the 32 most recent `(value, version)`
  pairs for MVCC snapshot reads.
- **`lock`** — a `ReentrantLock` acquired eagerly on `write()` in pessimistic mode
  (ownership stealing) and acquired at commit time in optimistic mode.

### Tx

`Tx` represents a single transaction attempt. It is created fresh on every retry.

| Field       | Type                       | Purpose                                                          |
|-------------|----------------------------|------------------------------------------------------------------|
| `readPoint` | `long`                     | `TxClock.current()` at construction; snapshot isolation baseline |
| `rs`        | `HashMap<TVar<?>, Long>`   | Read-set: observed version per TVar, validated at commit         |
| `ws`        | `TreeMap<TVar<?>, Object>` | Write-set: sorted by `TVar.id` for deadlock-free locking         |
| `stolen`    | `List<TVar<?>>`            | Pessimistic locks held; released by `unstealAll()` on abort      |

**Read path (optimistic):** validates `version ≤ readPoint`, checks the TVar is not
currently locked by another writer, scans `RingBufferHistory` for the snapshot value,
then re-validates `version` hasn't changed (double-checked locking pattern). Any
inconsistency throws `ConcurrentModificationException`.

**Read path (pessimistic):** records the current version in the read-set and returns
`tvar.value` directly. No history scan needed because the write lock prevents concurrent
mutation.

**Write path (optimistic):** buffers the value in `ws`. Locks are not acquired until
`commitOptimistic()`.

**Write path (pessimistic):** calls `steal(tvar)` before buffering. `steal` is
all-or-nothing: if `tryLock()` fails, all previously stolen locks are released
(`unstealAll()`) and a `ConcurrentModificationException` is thrown to trigger a retry.

**Commit (optimistic):** locks all write-set TVars in `TVar.id` order, validates the full
read-set, applies writes under a single `TxClock.next()` stamp, then unlocks.

**Commit (pessimistic):** validates the read-set (locks already held), applies writes,
then releases all stolen locks.

### RingBufferHistory

A pre-allocated, zero-allocation-after-construction MVCC ring buffer.

- Capacity: 32 slots (power of two; `slot = cursor & 31`).
- `append`: increments `cursor` atomically, writes `versions[slot]` then uses
  `VarHandle.setRelease` on `values[slot]` for release ordering.
- `scan(readPoint)`: walks slots from newest to oldest using `VarHandle.getAcquire`,
  returning the newest entry whose `version ≤ readPoint`. Throws `VersionEvictedException`
  if the requested snapshot has been overwritten by more than 32 subsequent commits.
- `VersionEvictedException` is caught by `IASTM.run()` and treated identically to a
  conflict — the transaction retries with a fresh `readPoint`.

### TxClock / TVarClock

Two separate `AtomicLong`-backed counters with distinct responsibilities:

| Clock       | Scope  | Purpose                                                                              |
|-------------|--------|--------------------------------------------------------------------------------------|
| `TxClock`   | Global | Logical commit clock. `current()` sets `readPoint`; `next()` assigns commit version. |
| `TVarClock` | Global | TVar identity counter. `next()` assigns each TVar's unique `id` at construction.     |

The separation keeps the commit clock free of noise from TVar construction, preserving
the monotonic invariant that `readPoint ≤ commitVersion` for any write seen by a
transaction.

### AdaptiveBackoff

Three-phase retry delay to avoid thundering-herd under high contention:

| Attempt range | Behaviour                                          |
|---------------|----------------------------------------------------|
| 1 – 8         | `Thread.onSpinWait()` — yield to the CPU scheduler |
| 9 – 14        | Exponential sleep: 1 ms × 2ⁿ, capped at 16 ms      |
| 15+           | Exponential sleep: 2 ms × 2ⁿ, capped at 256 ms     |

All phases add uniform random jitter of 0–5 ms to desynchronize competing threads.
The `AdaptiveBackoff` instance is scoped to a single `start` call via `ScopedValue`
so the delay grows monotonically across retries rather than resetting on each attempt.

### IASTMAgent / IASTMTransformer

The agent uses the **Java Class-File API** (JDK 24+) for bytecode transformation — no ASM
dependency. `ByteBuddy` is a compile-scope dependency of `iastm-agent` used only to obtain
an `Instrumentation` handle via `ByteBuddyAgent.install()` in the programmatic attach path;
it is not involved in the transformation itself.

`IASTMAgent` supports three activation modes:

| Mode         | Mechanism                                                                                      |
|--------------|------------------------------------------------------------------------------------------------|
| Static       | `-javaagent:iastm-agent.jar=com/example/` at JVM startup                                       |
| Dynamic      | `agentmain` via Attach API on a running JVM                                                    |
| Programmatic | `IASTMAgent.attach("com/example/")` in tests — uses `ByteBuddyAgent.install()` for self-attach |

The agent argument is a comma-separated list of binary-name prefixes (dots or slashes
both accepted). Classes outside the configured prefixes are never transformed.

`IASTMTransformer` performs the three-pass rewrite described in
[How it works](#how-it-works). If a class contains no bare `IASTM.start(Runnable)` call
sites the transformer returns `null` (no-op, original bytecode retained).

---

## Concurrency strategies

### Optimistic

Lock-free reads backed by MVCC snapshots. Conflicts are detected at commit time by
validating that each read TVar's version has not advanced past `readPoint`. Write locks
are acquired in `TVar.id` order at commit, held only for the duration of validation and
write application, then released immediately.

Best for: read-heavy workloads, low-to-medium write contention.

### Pessimistic

Write locks are stolen (eagerly acquired) at the first `IASTM.write()` call. Reads return
the live `tvar.value` under the assumption that the lock prevents concurrent mutation.
Read-set validation still runs at commit to catch races on TVars that were read but not
written.

If any `tryLock()` fails during stealing, all held locks are released immediately and the
transaction retries — avoiding deadlock without requiring a global lock order at steal
time. At commit the write locks are already held, so only validation and application
remain.

Best for: write-heavy workloads where optimistic aborts would dominate.

---

## MVCC and snapshot isolation

Every `TVar` maintains a 32-entry ring of past `(value, version)` pairs. A transaction
reading at `readPoint = T` will always see the value as it was at time `T`, even if the
TVar has been committed to several times since. This provides **snapshot isolation** with
no read locks and no blocking between readers and writers.

The 32-slot window is a tunable trade-off: wider windows increase memory usage per TVar
but reduce `VersionEvictedException` frequency under sustained write pressure on a single
variable.

---

## Deadlock prevention

Write locks are always acquired in ascending `TVar.id` order. Because `TVar.id` values
are assigned by a single global `AtomicLong` counter at construction, this order is
consistent across all threads for the lifetime of the JVM. The `TreeMap<TVar<?>, Object>`
write-set in `Tx` maintains this order automatically via `TVar.compareTo`.

In pessimistic mode, the all-or-nothing `steal` protocol ensures a thread never holds a
subset of locks while waiting: if any acquisition fails, all held locks are dropped before
retrying.

---

## Error handling

| Exception                         | Cause                                                  | Handling                                              |
|-----------------------------------|--------------------------------------------------------|-------------------------------------------------------|
| `ConcurrentModificationException` | Read-set version conflict or lock contention at commit | Caught by `IASTM.run`; retried with backoff           |
| `VersionEvictedException`         | Snapshot overwritten (>32 writes since `readPoint`)    | Caught by `IASTM.run`; retried with fresh `readPoint` |
| `MaxRetriesExceededException`     | 512 consecutive retry attempts exhausted               | Propagated to the caller                              |

---

## Modules

| Module            | Description                                                          |
|-------------------|----------------------------------------------------------------------|
| `iastm-core`      | `IASTM`, `TVar`, `Tx`, clocks, MVCC, backoff, exceptions             |
| `iastm-agent`     | Java agent + bytecode transformer (`IASTMAgent`, `IASTMTransformer`) |
| `iastm-benchmark` | JMH benchmark suite (`IASTMBenchmark`)                               |

---

## Requirements

- JDK 25 (preview features enabled)
- Maven 3.9+

---

## Running benchmarks

```bash
java --enable-preview -jar iastm-benchmark/target/benchmarks.jar
```

To run a specific benchmark or filter by name:

```bash
java --enable-preview -jar iastm-benchmark/target/benchmarks.jar bankTransfer
```

---

## Benchmarks

All benchmarks are in `IASTMBenchmark` (`@State(Scope.Benchmark)`, 3 warmup + 5 measurement
iterations × 2 forks). Shared state is re-initialized per iteration (`@Setup(Level.Iteration)`)
to prevent counter overflow or balance depletion from skewing timing.

**Workloads**

| Benchmark group      | Body                                                                                                      | Strategy forced via                                      |
|----------------------|-----------------------------------------------------------------------------------------------------------|----------------------------------------------------------|
| `baseline_read`      | Single `IASTM.read` on one `TVar`, result consumed by `Blackhole`                                         | `OPTIMISTIC` (default)                                   |
| `baseline_increment` | Single `IASTM.read` + `IASTM.write` on one `TVar`                                                         | `OPTIMISTIC` (default)                                   |
| `multiRead`          | Reads two adjacent accounts from a 64-element array, sums balances; pair chosen randomly per call         | `OPTIMISTIC` (default)                                   |
| `bankTransfer`       | Reads source balance; if > 0, decrements source and increments destination; both accounts chosen randomly | `TxMetrics(1,9)` → `PESSIMISTIC` or default `OPTIMISTIC` |
| `mixed_readHeavy`    | Reads 8 consecutive accounts, increments one "sink" account on the opposite side of the array             | `TxMetrics(9,1)` → `OPTIMISTIC`                          |

The 64-account pool (`ACCOUNT_COUNT = 64`, each initialized to 100,000) provides enough
variables to spread contention across threads while keeping conflict rates measurable.

**Results** — JDK 25.0.3, OpenJDK 64-Bit Server VM, AMD Ryzen 5 7535HS (12 threads) @ 4.60 GHz.
Throughput in ops/s, higher is better.

| Benchmark                     | Threads | ops/s      | Error        |
|-------------------------------|---------|------------|--------------|
| `baseline_read_1t`            | 1       | 10,893,427 | ± 181,686    |
| `baseline_increment_1t`       | 1       | 7,311,142  | ± 434,885    |
| `multiRead_optimistic_4t`     | 4       | 28,759,901 | ± 12,543,127 |
| `multiRead_optimistic_8t`     | 8       | 38,720,213 | ± 6,727,568  |
| `bankTransfer_optimistic_4t`  | 4       | 4,899,352  | ± 1,137,914  |
| `bankTransfer_optimistic_8t`  | 8       | 5,671,968  | ± 511,144    |
| `bankTransfer_pessimistic_4t` | 4       | 6,106,656  | ± 839,891    |
| `bankTransfer_pessimistic_8t` | 8       | 7,401,965  | ± 397,720    |
| `mixed_readHeavy_4t`          | 4       | 3,228,018  | ± 202,870    |
| `mixed_readHeavy_8t`          | 8       | 3,162,629  | ± 171,668    |

**`multiRead`** scales past 8 threads because reads are non-contending MVCC snapshots —
each thread scans its own `readPoint` without acquiring any lock.

**`bankTransfer` optimistic** throughput drops at 8t due to write conflicts and retry
overhead. **`bankTransfer` pessimistic** holds steady by acquiring locks eagerly and
avoiding wasted optimistic work; the adaptive strategy selector routes write-heavy
workloads here automatically.
