package tech.provokedynamic.iastm;

/// Immutable snapshot of a transaction's declared operation profile.
///
/// Passed to [tech.provokedynamic.iastm.atomic.IASTM#start(Runnable, TxMetrics)]
/// to let the runtime select an appropriate concurrency strategy before the
/// transaction begins. Values are supplied by the caller based on prior knowledge
/// of the workload (e.g. from bytecode instrumentation or manual annotation).
///
/// @param readOps  expected number of `TVar` reads in this transaction
/// @param writeOps expected number of `TVar` writes in this transaction
/// @param totalOps sum of `readOps` and `writeOps`; derived automatically
///                 by the two-argument constructor
public record TxMetrics(
        int readOps,
        int writeOps,
        int totalOps
) {
    /// Convenience constructor that derives `totalOps` automatically.
    ///
    /// @param readOps  number of expected read operations
    /// @param writeOps number of expected write operations
    public TxMetrics(int readOps, int writeOps) {
        this(readOps, writeOps, readOps + writeOps);
    }
}
