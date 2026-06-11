package tech.provokedynamic.iastm.exception;

/// Thrown when a transaction exhausts its retry budget without committing.
///
/// [tech.provokedynamic.iastm.atomic.IASTM] allows up to `MAX_RETRY` (512)
/// attempts per top-level [tech.provokedynamic.iastm.atomic.IASTM#start] call.
/// If the transaction has not committed by then — typically because of sustained
/// high contention — this unchecked exception propagates to the caller to signal
/// that progress could not be guaranteed.
public final class MaxRetriesExceededException extends RuntimeException {

    /// Constructs the exception with the limit that was exceeded.
    ///
    /// @param maxRetries the retry budget that was exhausted
    public MaxRetriesExceededException(int maxRetries) {
        super("Transaction exceeded maximum retry limit of " + maxRetries);
    }
}
