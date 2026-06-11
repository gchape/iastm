package tech.provokedynamic.iastm;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class TxMetricsTest {

    @Test
    void derivesTotalOps() {
        var m = new TxMetrics(3, 2);
        assertThat(m.totalOps()).isEqualTo(5);
    }

    @Test
    void threeArgConstructorPreservesTotalOps() {
        var m = new TxMetrics(3, 2, 99);
        assertThat(m.totalOps()).isEqualTo(99);
    }

    @Test
    void zeroOps() {
        var m = new TxMetrics(0, 0);
        assertThat(m.totalOps()).isZero();
    }
}
