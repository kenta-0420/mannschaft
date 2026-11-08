package com.mannschaft.app.reservation;

import com.mannschaft.app.reservation.entity.ReservationPendingExpireScanStateEntity;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Entityの引数拒否を確認する純Java試験。実DB/実proxyの証明とはしない。 */
class ReservationPendingExpireScanStateTest {

    @Test
    void null更新時刻は全mutationで変更前に拒否する() {
        var now = Instant.parse("2026-10-07T00:00:00Z");
        assertThatThrownBy(() -> ReservationPendingExpireScanStateEntity.initial(null))
                .isInstanceOf(IllegalArgumentException.class);
        var state = ReservationPendingExpireScanStateEntity.initial(now);
        state.beginRun(20, now);
        state.enqueue(10, now);
        assertThatThrownBy(() -> state.beginRun(999, null)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> state.enqueue(11, null)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> state.advance(10, null)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> state.removeRetry(10, null)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> state.finishCycle(null)).isInstanceOf(IllegalArgumentException.class);
        state.validate();
        assertThat(state.getRunEpoch()).isEqualTo(1);
        assertThat(state.getCycleHighWater()).isEqualTo(20);
        assertThat(state.getLastInspectedId()).isZero();
        assertThat(state.getRetryPrimaryIds()).containsExactly(10L);
        assertThat(state.getUpdatedAt()).isEqualTo(now);
    }
}
