package com.mannschaft.app.reservation.service;

import com.mannschaft.app.common.i18n.UserLocaleCache;
import com.mannschaft.app.common.timezone.TeamTimezoneResolver;
import com.mannschaft.app.notification.service.NotificationHelper;
import com.mannschaft.app.reservation.ReservationStatus;
import com.mannschaft.app.reservation.entity.ReservationPolicyEntity;
import com.mannschaft.app.reservation.repository.ReservationPolicyRepository;
import com.mannschaft.app.reservation.repository.ReservationRepository;
import com.mannschaft.app.reservation.repository.ReservationSlotRepository;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.MessageSource;
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
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

/**
 * 旧bookedAtのJST保持形式に合わせ、固定瞬間をAsia/Tokyoの壁時計へ渡す番人。
 * ClockのUTC/JST設定に引きずられず、03Zを12:00として扱うことをliteralで検証する。
 * JVM既定ゾーンを変更せず、通常JSTとNon-JST CIの実workerで同じ契約を確認する。
 * 既bookedAt列/データのUTC移行をこの試験の範囲へ広げない。
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
    @Mock
    private UserLocaleCache userLocaleCache;
    @Mock
    private MessageSource messageSource;

    @Mock private ReservationPolicyRepository policyRepository;
    @Mock private ReservationPendingExpireProgressService progress;
    @Mock private TeamTimezoneResolver timezoneResolver;
    @Mock private EntityManager entityManager;

    private ReservationPendingExpireService serviceWith(Clock clock) {
        return new ReservationPendingExpireService(
                reservationRepository, slotRepository, slotService, notificationHelper,
                userLocaleCache, messageSource, clock, policyRepository, progress, timezoneResolver, entityManager);
    }

    private void stubEmptyResult() {
        given(reservationRepository.findPendingExpireHighWater()).willReturn(1L);
        given(reservationRepository.findPendingPrimaryCandidates(
                eq(ReservationStatus.PENDING), anyLong(), anyLong(), any(), anyInt(), any(Pageable.class)))
                .willReturn(List.of());
    }

    @Test
    @DisplayName("Clock UTC/JSTやJVM既定に引きずられず、旧JST保持形式の12:00で一致する")
    void 判定時刻はClockのゾーンに左右されず既定ゾーン基準になる() {
        stubEmptyResult();

        // 同じ瞬間を指すが「ゾーン設定だけが違う」2 つの Clock で抽出させる。
        serviceWith(Clock.fixed(FIXED_INSTANT, ZoneOffset.UTC)).findExpirableUnits();
        serviceWith(Clock.fixed(FIXED_INSTANT, ZoneId.of("Asia/Tokyo"))).findExpirableUnits();

        ArgumentCaptor<LocalDateTime> now = ArgumentCaptor.forClass(LocalDateTime.class);
        verify(reservationRepository, times(2)).findPendingPrimaryCandidates(
                eq(ReservationStatus.PENDING), anyLong(), anyLong(), now.capture(), anyInt(), any(Pageable.class));
        List<LocalDateTime> passed = now.getAllValues();

        // 同じ瞬間を指す Clock なら、ゾーン設定が何であれ判定時刻は同一でなければならない。
        // 実装が LocalDateTime.now(clock)（＝Clock のゾーンをそのまま採用）だと、この 2 値は 9 時間ずれる。
        assertThat(passed.get(0))
                .as("Clock のゾーン設定が判定結果に漏れ出してはならない")
                .isEqualTo(passed.get(1));

        // 実装定数を再使用せず、既JST保持形式の03Z→12:00をliteralで固定する。
        LocalDateTime expected = LocalDateTime.of(2026, 7, 29, 12, 0);
        assertThat(passed.get(0))
                .as("旧JST保持形式をClock/JVM既定へ引きずらず12:00で測る")
                .isEqualTo(expected);
    }

    @Test
    @DisplayName("1回あたりの取得上限（500単位）が Pageable でクエリへ渡る")
    void 取得上限がクエリへ渡る() {
        ReservationPendingExpireService service = new ReservationPendingExpireService(
                reservationRepository, slotRepository, slotService, notificationHelper,
                userLocaleCache, messageSource,
                Clock.fixed(FIXED_INSTANT, ZoneOffset.UTC), policyRepository, progress, timezoneResolver, entityManager);
        given(reservationRepository.findPendingExpireHighWater()).willReturn(1L);
        given(reservationRepository.findPendingPrimaryCandidates(
                eq(ReservationStatus.PENDING), anyLong(), anyLong(), any(), anyInt(), any(Pageable.class)))
                .willReturn(List.of());

        service.findExpirableUnits();

        ArgumentCaptor<Pageable> pageable = ArgumentCaptor.forClass(Pageable.class);
        verify(reservationRepository).findPendingPrimaryCandidates(
                eq(ReservationStatus.PENDING), anyLong(), anyLong(), any(), anyInt(), pageable.capture());
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
                userLocaleCache, messageSource,
                Clock.fixed(FIXED_INSTANT, ZoneOffset.UTC), policyRepository, progress, timezoneResolver, entityManager);
        given(reservationRepository.findPendingExpireHighWater()).willReturn(1L);
        given(reservationRepository.findPendingPrimaryCandidates(
                eq(ReservationStatus.PENDING), anyLong(), anyLong(), any(), anyInt(), any(Pageable.class)))
                .willReturn(List.of());

        service.findExpirableUnits();

        ArgumentCaptor<Integer> defaultHours = ArgumentCaptor.forClass(Integer.class);
        verify(reservationRepository).findPendingPrimaryCandidates(
                eq(ReservationStatus.PENDING), anyLong(), anyLong(), any(), defaultHours.capture(),
                any(Pageable.class));
        assertThat(defaultHours.getValue())
                .isEqualTo(ReservationPolicyEntity.DEFAULT_PENDING_EXPIRE_HOURS);
    }
}
