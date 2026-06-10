package tech.provokedynamic.iastm;

public record TxMetrics(
        int readOps,
        int writeOps,
        int totalOps
) {
    public TxMetrics(int readOps, int writeOps) {
        this(readOps, writeOps, readOps + writeOps);
    }
}
