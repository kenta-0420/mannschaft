package com.mannschaft.app.reservation.service;

import com.mannschaft.app.notification.service.NotificationHelper;
import com.mannschaft.app.reservation.ReservationStatus;
import com.mannschaft.app.reservation.entity.ReservationPolicyEntity;
import com.mannschaft.app.reservation.repository.ReservationRepository;
import com.mannschaft.app.reservation.repository.ReservationSlotRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Pageable;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

/**
 * 失効判定の「現在時刻」が <b>{@code booked_at} と同じ時間基準（JVM 既定ゾーン）</b>で
 * 組み立てられることを、ゾーンを明示的に切り替えて検証する番人テスト（家老指摘⑤）。
 *
 * <h2>このテストが無いと何が起きるか</h2>
 * <p>{@code Clock} Bean は UTC 固定（{@code ClockConfig#utcClock}）だが、
 * {@code ReservationEntity.bookedAt} は {@code LocalDateTime.now()}（JVM 既定ゾーン）で書かれる。
 * 実装が {@code LocalDateTime.now(clock)} のまま（＝UTC 基準）だと、JST 環境では経過時間が
 * 9 時間短く見積もられ「24 時間で自動キャンセル」が<b>実質 33 時間</b>になる。
 * これは W2-6 の実 MySQL 結合テストで実際に検出した中核バグである。</p>
 *
 * <p>ところが <b>CI の JVM 既定ゾーンは UTC</b> のため、
 * {@code clock.withZone(ZoneId.systemDefault())} を {@code LocalDateTime.now(clock)} に戻しても
 * CI では全テストが緑のまま通ってしまい、退行が JST 環境でしか露見しない。
 * 本テストは既定ゾーンに依存せず、<b>Clock のゾーンだけを UTC / +09:00 に振って</b>
 * 「渡される {@code now} が既定ゾーン基準で一致する」ことを直接観測することで、
 * その退行を CI 上でも捕まえる。</p>
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("仮押さえ自動失効 判定時刻のゾーン基準 番人テスト（家老指摘⑤）")
class ReservationPendingExpireServiceClockZoneTest {

    /** 判定の基準となる瞬間（絶対時刻）。ゾーンが変わっても同じ瞬間を指す。 */
    private static final Instant FIXED_INSTANT = Instant.parse("2026-07-29T03:00:00Z");

    @Mock
    private ReservationRepository reservationRepository;
    @Mock
    private ReservationSlotRepository slotRepository;
    @Mock
    private ReservationSlotService slotService;
    @Mock
    private NotificationHelper notificationHelper;

    private ReservationPendingExpireService serviceWith(Clock clock) {
        return new ReservationPendingExpireService(
                reservationRepository, slotRepository, slotService, notificationHelper, clock);
    }

    private void stubEmptyResult() {
        given(reservationRepository.findExpirablePendingPrimaryRows(
                eq(ReservationStatus.PENDING), any(), any(), any(), anyInt(), any(Pageable.class)))
                .willReturn(List.of());
    }

    @Test
    @DisplayName("Clock のゾーンが UTC でも +09:00 でも、判定時刻は JVM 既定ゾーン基準で一致する")
    void 判定時刻はClockのゾーンに左右されず既定ゾーン基準になる() {
        stubEmptyResult();

        // 同じ瞬間を指すが「ゾーン設定だけが違う」2 つの Clock で抽出させる。
        serviceWith(Clock.fixed(FIXED_INSTANT, ZoneOffset.UTC)).findExpirableUnits();
        serviceWith(Clock.fixed(FIXED_INSTANT, ZoneId.of("Asia/Tokyo"))).findExpirableUnits();

        ArgumentCaptor<LocalDateTime> now = ArgumentCaptor.forClass(LocalDateTime.class);
        verify(reservationRepository, times(2)).findExpirablePendingPrimaryRows(
                eq(ReservationStatus.PENDING), now.capture(), any(), any(), anyInt(), any(Pageable.class));
        List<LocalDateTime> passed = now.getAllValues();

        // 同じ瞬間を指す Clock なら、ゾーン設定が何であれ判定時刻は同一でなければならない。
        // 実装が LocalDateTime.now(clock)（＝Clock のゾーンをそのまま採用）だと、この 2 値は 9 時間ずれる。
        assertThat(passed.get(0))
                .as("Clock のゾーン設定が判定結果に漏れ出してはならない")
                .isEqualTo(passed.get(1));

        // かつ、その値は「その瞬間を JVM 既定ゾーンで見た壁時計」＝ booked_at と同じ基準であること。
        LocalDateTime expected = LocalDateTime.ofInstant(FIXED_INSTANT, ZoneId.systemDefault());
        assertThat(passed.get(0))
                .as("booked_at は LocalDateTime.now()（JVM 既定ゾーン）で書かれるため同一基準で測る")
                .isEqualTo(expected);
    }

    @Test
    @DisplayName("1回あたりの取得上限（500単位）が Pageable でクエリへ渡る")
    void 取得上限がクエリへ渡る() {
        ReservationPendingExpireService service = new ReservationPendingExpireService(
                reservationRepository, slotRepository, slotService, notificationHelper,
                Clock.fixed(FIXED_INSTANT, ZoneOffset.UTC));
        given(reservationRepository.findExpirablePendingPrimaryRows(
                eq(ReservationStatus.PENDING), any(), any(), any(), anyInt(), any(Pageable.class)))
                .willReturn(List.of());

        service.findExpirableUnits();

        ArgumentCaptor<Pageable> pageable = ArgumentCaptor.forClass(Pageable.class);
        verify(reservationRepository).findExpirablePendingPrimaryRows(
                eq(ReservationStatus.PENDING), any(), any(), any(), anyInt(), pageable.capture());
        assertThat(pageable.getValue().getPageSize())
                .as("上限が無いとデプロイ初回に一斉失効・通知バーストが起きる（殿の裁定1）")
                .isEqualTo(ReservationPendingExpireService.MAX_UNITS_PER_RUN);
        assertThat(pageable.getValue().getPageNumber()).isZero();
    }

    @Test
    @DisplayName("ポリシー行が無いチームの既定時間（24）がクエリへ渡る")
    void 既定時間がクエリへ渡る() {
        ReservationPendingExpireService service = new ReservationPendingExpireService(
                reservationRepository, slotRepository, slotService, notificationHelper,
                Clock.fixed(FIXED_INSTANT, ZoneOffset.UTC));
        given(reservationRepository.findExpirablePendingPrimaryRows(
                eq(ReservationStatus.PENDING), any(), any(), any(), anyInt(), any(Pageable.class)))
                .willReturn(List.of());

        service.findExpirableUnits();

        ArgumentCaptor<Integer> defaultHours = ArgumentCaptor.forClass(Integer.class);
        verify(reservationRepository).findExpirablePendingPrimaryRows(
                eq(ReservationStatus.PENDING), any(), any(), any(), defaultHours.capture(),
                any(Pageable.class));
        assertThat(defaultHours.getValue())
                .isEqualTo(ReservationPolicyEntity.DEFAULT_PENDING_EXPIRE_HOURS);
    }
}
