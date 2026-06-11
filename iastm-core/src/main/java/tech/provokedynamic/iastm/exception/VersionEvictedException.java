package tech.provokedynamic.iastm.exception;

import tech.provokedynamic.iastm.mvcc.RingBufferHistory;

/// Thrown by [RingBufferHistory#scan(long)] when the requested `readPoint`
/// predates all retained entries — i.e. the TVar was written more than 32 times
/// since the transaction started and the snapshot was overwritten.
///
/// Caught by [IASTM#run(Runnable)], which retries with a fresh `readPoint`.
public final class VersionEvictedException extends RuntimeException {

    public VersionEvictedException(long requested) {
        super("Version " + requested + " has been evicted from history.");
    }
}
