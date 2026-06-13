package tech.provokedynamic.iastm.mvcc;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tech.provokedynamic.iastm.exception.VersionEvictedException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class RingBufferHistoryTest {

    @Test
    @DisplayName("scan returns initial value at version 0")
    void scanInitial() {
        AdaptiveRingHistory<String> h = new AdaptiveRingHistory<>("init");
        assertThat(h.scan(0L)).isEqualTo("init");
    }

    @Test
    @DisplayName("scan returns latest value at or before readPoint")
    void scanSnapshotSemantics() {
        AdaptiveRingHistory<Integer> h = new AdaptiveRingHistory<>(0);
        h.append(10, 1L);
        h.append(20, 3L);
        h.append(30, 5L);

        assertThat(h.scan(0L)).isEqualTo(0);
        assertThat(h.scan(1L)).isEqualTo(10);
        assertThat(h.scan(2L)).isEqualTo(10);
        assertThat(h.scan(3L)).isEqualTo(20);
        assertThat(h.scan(4L)).isEqualTo(20);
        assertThat(h.scan(5L)).isEqualTo(30);
        assertThat(h.scan(Long.MAX_VALUE)).isEqualTo(30);
    }

    @Test
    @DisplayName("scan throws VersionEvictedException after 32 overwrites")
    void scanThrowsWhenEvicted() {
        AdaptiveRingHistory<Integer> h = new AdaptiveRingHistory<>(0);
        for (int i = 1; i <= 33; i++) {
            h.append(i, i);
        }
        // readPoint 0 is now beyond the ring window
        assertThatThrownBy(() -> h.scan(0L))
                .isInstanceOf(VersionEvictedException.class)
                .hasMessageContaining("0");
    }

    @Test
    @DisplayName("append wraps slot correctly at boundary")
    void appendWrapsAtBoundary() {
        AdaptiveRingHistory<Integer> h = new AdaptiveRingHistory<>(0);
        // fill exactly 32 slots after the initial one
        for (int i = 1; i <= 32; i++) {
            h.append(i * 100, i);
        }
        // the last 32 entries should be visible
        assertThat(h.scan(32L)).isEqualTo(3200);
        assertThat(h.scan(16L)).isEqualTo(1600);
    }

    @Test
    @DisplayName("VersionEvictedException message includes readPoint")
    void evictedExceptionMessage() {
        assertThatThrownBy(() -> {
            throw new VersionEvictedException(42L);
        }).hasMessageContaining("42");
    }
}
