package tech.provokedynamic.iastm.exception;

/// Thrown when a transaction's `readPoint` predates all versions retained in a
/// [tech.provokedynamic.iastm.mvcc.VersionHistory] ring-buffer.
///
/// This happens when a `TVar` has been written more than `MAX_HISTORY` (32) times
/// since the transaction started, causing the snapshot the transaction needed to
/// have been silently overwritten. The runtime catches this in
/// [tech.provokedynamic.iastm.atomic.IASTM#run(Runnable)] and triggers a retry
/// so the transaction can restart with a current `readPoint`.
public final class VersionEvictedException extends RuntimeException {

    /// Constructs the exception with a message identifying the evicted snapshot.
    ///
    /// @param requested the `readPoint` for which no version could be found
    public VersionEvictedException(long requested) {
        super("Version " + requested + " has been evicted from history.");
    }
}
