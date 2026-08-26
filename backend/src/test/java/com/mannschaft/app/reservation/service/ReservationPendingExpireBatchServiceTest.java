package com.mannschaft.app.reservation.service;

import com.mannschaft.app.reservation.ReservationStatus;
import com.mannschaft.app.reservation.entity.ReservationEntity;
import com.mannschaft.app.reservation.service.ReservationPendingExpireService.PendingExpireUnit;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

/**
 * 仮押さえ自動失効バッチの堅牢性テスト（F03.4.5 §6.3・受け入れ条件 AC-6-9 / AC-6-10）。
 *
 * <p>時刻境界・グループ原子性・枠復帰・N+1 は実 MySQL の
 * {@code ReservationPendingExpirePersistenceIntegrationTest} が検証する。本クラスは
 * 「1 単位の失敗が他を巻き込まない」「0 件で副作用ゼロ」という<b>バッチの制御構造</b>のみを、
 * 障害注入しやすい Mockito で検証する（実処理は委譲先が担うため、ここでの mock は
 * 自前 Bean 1 個に限定される）。</p>
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("仮押さえ自動失効バッチ 堅牢性テスト（F03.4.5 §6.3）")
class ReservationPendingExpireBatchServiceTest {

    @Mock
    private ReservationPendingExpireService pendingExpireService;

    @InjectMocks
    private ReservationPendingExpireBatchService batchService;

    private PendingExpireUnit unit(Long reservationId, Long teamId) {
        ReservationEntity primary = ReservationEntity.builder()
                .id(reservationId)
                .reservationSlotId(reservationId * 10)
                .lineId(1L)
                .teamId(teamId)
                .userId(9000L + reservationId)
                .status(ReservationStatus.PENDING)
                .isGroupPrimary(true)
                .bookedAt(LocalDateTime.of(2026, 7, 26, 12, 0))
                .build();
        return new PendingExpireUnit(primary, List.of(primary), Map.of());
    }

    // ────────────────────────────────────────────────────────────
    // AC-6-10: 対象 0 件でも例外なく完了し、通知・イベントを 1 件も出さない
    // ────────────────────────────────────────────────────────────

    @Test
    @DisplayName("AC-6-10: 対象0件なら副作用ゼロで 0 件を返す")
    void 対象0件は副作用ゼロ() {
        given(pendingExpireService.findExpirableUnits()).willReturn(List.of());

        int expired = batchService.expirePendingReservations();

        assertThat(expired).as("失効件数は 0").isZero();
        // 失効処理（＝通知・枠復帰・イベント発火を含む唯一の入口）が一度も呼ばれない
        verify(pendingExpireService, never()).expireUnit(any());
    }

    @Test
    @DisplayName("AC-6-10: 対象0件でも例外を投げない")
    void 対象0件でも例外を投げない() {
        given(pendingExpireService.findExpirableUnits()).willReturn(List.of());

        assertThatCode(() -> batchService.expirePendingReservations()).doesNotThrowAnyException();
    }

    // ────────────────────────────────────────────────────────────
    // AC-6-9: 1 単位の処理が例外を投げても残りは処理継続する（単位ごと try/catch）
    // ────────────────────────────────────────────────────────────

    @Test
    @DisplayName("AC-6-9: 1件目が例外を投げても2・3件目は処理され、バッチ自体は例外を投げない")
    void 一件の失敗が他を巻き込まない() {
        PendingExpireUnit failing = unit(1L, 100L);
        PendingExpireUnit healthyA = unit(2L, 100L);
        PendingExpireUnit healthyB = unit(3L, 200L);
        given(pendingExpireService.findExpirableUnits())
                .willReturn(List.of(failing, healthyA, healthyB));
        // 1 件目で DB 障害等を注入する。
        given(pendingExpireService.expireUnit(failing))
                .willThrow(new IllegalStateException("枠復帰で想定外の障害"));
        given(pendingExpireService.expireUnit(healthyA)).willReturn(1);
        given(pendingExpireService.expireUnit(healthyB)).willReturn(3);

        int expired = batchService.expirePendingReservations();

        assertThat(expired)
                .as("失敗した 1 件目を除き、2 件目(1行)＋3 件目(グループ3行)が計上される")
                .isEqualTo(4);
        // 後続 2 単位が確かに処理されている（＝ループが中断していない）
        verify(pendingExpireService, times(1)).expireUnit(healthyA);
        verify(pendingExpireService, times(1)).expireUnit(healthyB);
    }

    @Test
    @DisplayName("AC-6-9: 全件が例外でもバッチは落ちず 0 件を返す")
    void 全件失敗でもバッチは落ちない() {
        PendingExpireUnit a = unit(1L, 100L);
        PendingExpireUnit b = unit(2L, 100L);
        given(pendingExpireService.findExpirableUnits()).willReturn(List.of(a, b));
        given(pendingExpireService.expireUnit(any()))
                .willThrow(new IllegalStateException("DB 障害"));

        int expired = batchService.expirePendingReservations();

        assertThat(expired).isZero();
        verify(pendingExpireService, times(2)).expireUnit(any());
    }
}
