package tech.provokedynamic.iastm.exception;

/// Thrown when a transaction exhausts its 512-attempt retry budget.
///
/// Signals to the caller that the transaction could not make progress,
/// typically due to sustained high contention.
public final class MaxRetriesExceededException extends RuntimeException {

    public MaxRetriesExceededException(int maxRetries) {
        super("Transaction exceeded maximum retry limit of " + maxRetries);
    }
}
