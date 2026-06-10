package tech.provokedynamic.iastm.exception;

public final class VersionEvictedException extends RuntimeException {

    public VersionEvictedException(long requested) {
        super("Version " + requested + " has been evicted from history.");
    }
}
