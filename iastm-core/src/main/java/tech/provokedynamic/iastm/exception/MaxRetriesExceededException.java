package tech.provokedynamic.iastm.exception;

public final class MaxRetriesExceededException extends RuntimeException {

    public MaxRetriesExceededException(int maxRetries) {
        super("Transaction exceeded maximum retry limit of " + maxRetries);
    }
}
