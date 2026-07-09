package com.mannschaft.app.reservation.service;

import com.mannschaft.app.reservation.ReservationMapper;
import com.mannschaft.app.reservation.SlotStatus;
import com.mannschaft.app.reservation.entity.ReservationSlotEntity;
import com.mannschaft.app.reservation.event.ReservationSlotReopenedEvent;
import com.mannschaft.app.reservation.repository.ReservationBlockedTimeRepository;
import com.mannschaft.app.reservation.repository.ReservationLineRepository;
import com.mannschaft.app.reservation.repository.ReservationRepository;
import com.mannschaft.app.reservation.repository.ReservationSlotRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;

import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link ReservationSlotService#decrementAndReopen} が満席→空き復帰時にのみ
 * {@link ReservationSlotReopenedEvent} を発行することを検証する（F03.4.5 §6.1・W-2 の起点）。
 *
 * <p>この単一点を全キャンセル経路（単枠・グループ・リスケ・緊急休業）が通るため、
 * ここでのイベント発行がキャンセル待ち一斉通知の唯一のトリガとなる。</p>
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("ReservationSlotService.decrementAndReopen 空き復帰イベント発行（F03.4.5 §6.1）")
class ReservationSlotServiceReopenEventTest {

    private static final Long TEAM_ID = 700L;
    private static final Long SLOT_ID = 8001L;

    @Mock
    private ReservationSlotRepository slotRepository;
    @Mock
    private ReservationRepository reservationRepository;
    @Mock
    private ReservationMapper reservationMapper;
    @Mock
    private ReservationBlockedTimeRepository blockedTimeRepository;
    @Mock
    private ReservationUnavailabilityChecker unavailabilityChecker;
    @Mock
    private ReservationLineRepository lineRepository;
    @Mock
    private ApplicationEventPublisher eventPublisher;

    private ReservationSlotService service;

    @BeforeEach
    void setUp() {
        service = new ReservationSlotService(
                slotRepository, reservationRepository, reservationMapper, blockedTimeRepository,
                unavailabilityChecker, lineRepository, Clock.systemUTC(), eventPublisher);
    }

    /**
     * 発火判定は in-memory スナップショットに依存しないため、entity の status は任意（あえて AVAILABLE で作る）。
     * これにより「DB が遷移を起こしたか（reopenSlotIfFull の戻り値）」だけがゲートであることを固定する。
     */
    private ReservationSlotEntity slot() {
        return ReservationSlotEntity.builder()
                .id(SLOT_ID).teamId(TEAM_ID)
                .slotDate(LocalDate.of(2026, 8, 1)).startTime(LocalTime.of(10, 0)).endTime(LocalTime.of(10, 30))
                .capacity(2).slotStatus(SlotStatus.AVAILABLE).build();
    }

    @Test
    @DisplayName("reopenSlotIfFull が 1（DB で FULL→AVAILABLE 遷移）を返したときだけイベントを発行する")
    void DB遷移ありでイベント発行() {
        when(slotRepository.reopenSlotIfFull(SLOT_ID)).thenReturn(1);

        // entity の in-memory status は AVAILABLE（スナップショットに依存しないことの確認）
        service.decrementAndReopen(slot());

        // デクリメント → reopen の順で呼ばれる
        verify(slotRepository).decrementBookedCount(SLOT_ID);
        verify(slotRepository).reopenSlotIfFull(SLOT_ID);
        ArgumentCaptor<ReservationSlotReopenedEvent> captor =
                ArgumentCaptor.forClass(ReservationSlotReopenedEvent.class);
        verify(eventPublisher).publishEvent(captor.capture());
        assertThat(captor.getValue().getSlotId()).isEqualTo(SLOT_ID);
        assertThat(captor.getValue().getTeamId()).isEqualTo(TEAM_ID);
    }

    @Test
    @DisplayName("reopenSlotIfFull が 0（遷移なし）のときはイベントを発行しない")
    void DB遷移なしでイベント無し() {
        when(slotRepository.reopenSlotIfFull(SLOT_ID)).thenReturn(0);

        service.decrementAndReopen(slot());

        verify(slotRepository).decrementBookedCount(SLOT_ID);
        verify(slotRepository).reopenSlotIfFull(SLOT_ID);
        verify(eventPublisher, never())
                .publishEvent(org.mockito.ArgumentMatchers.any(ReservationSlotReopenedEvent.class));
    }
}
