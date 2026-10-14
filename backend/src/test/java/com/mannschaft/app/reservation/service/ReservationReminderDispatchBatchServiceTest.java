package com.mannschaft.app.reservation.service;

import com.mannschaft.app.reservation.entity.ReservationEntity;
import com.mannschaft.app.reservation.entity.ReservationReminderEntity;
import com.mannschaft.app.reservation.entity.ReservationSlotEntity;
import com.mannschaft.app.reservation.event.ReservationReminderDispatchEvent;
import com.mannschaft.app.reservation.repository.ReservationRepository;
import com.mannschaft.app.reservation.repository.ReservationSlotRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.lang.reflect.Field;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

/**
 * {@link ReservationReminderDispatchBatchService} の単体テスト（F03.4 段階拡張 ⑥ / Issue #2997 G3）。
 *
 * <p>本バッチは<b>非 TX オーケストレータ</b>であり、通知は送らない。remind_at 到来済み PENDING について
 * reservation / slot を一括取得し、1 件ごとに {@link ReservationReminderDispatchRunner#publishOne} へ委譲する。
 * 実通知・印の先取り・戻しは配送リスナー側（{@code ReservationReminderDispatchNotificationListenerTest}）で検証する。
 * 解決不能（予約/枠なし）は publish せず PENDING のまま、1 件の失敗が他の行を巻き込まないことを番人化する。</p>
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("ReservationReminderDispatchBatchService 単体テスト")
class ReservationReminderDispatchBatchServiceTest {

    @Mock
    private ReservationReminderService reminderService;

    @Mock
    private ReservationRepository reservationRepository;

    @Mock
    private ReservationSlotRepository slotRepository;

    @Mock
    private ReservationReminderDispatchRunner reminderRunner;

    @InjectMocks
    private ReservationReminderDispatchBatchService batch;

    private static final Long RESERVATION_ID = 100L;
    private static final Long SLOT_ID = 200L;
    private static final Long TEAM_ID = 300L;
    private static final Long USER_ID = 400L;

    private static void setId(Object entity, Long id) {
        try {
            Field f = findIdField(entity.getClass());
            f.setAccessible(true);
            f.set(entity, id);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private static Field findIdField(Class<?> clazz) throws NoSuchFieldException {
        Class<?> c = clazz;
        while (c != null) {
            try {
                return c.getDeclaredField("id");
            } catch (NoSuchFieldException e) {
                c = c.getSuperclass();
            }
        }
        throw new NoSuchFieldException("id");
    }

    private ReservationReminderEntity pendingReminder(long id, long reservationId) {
        ReservationReminderEntity reminder = ReservationReminderEntity.builder()
                .reservationId(reservationId)
                .remindAt(LocalDateTime.of(2026, 4, 1, 9, 0))
                .build();
        setId(reminder, id);
        return reminder;
    }

    private ReservationEntity reservation(long id, long slotId, long userId) {
        ReservationEntity reservation = ReservationEntity.builder()
                .reservationSlotId(slotId)
                .lineId(1L)
                .teamId(TEAM_ID)
                .userId(userId)
                .build();
        setId(reservation, id);
        return reservation;
    }

    private ReservationSlotEntity slot(long id) {
        ReservationSlotEntity slot = ReservationSlotEntity.builder()
                .teamId(TEAM_ID)
                .title("整体60分コース")
                .slotDate(LocalDate.of(2026, 4, 2))
                .startTime(LocalTime.of(10, 0))
                .endTime(LocalTime.of(11, 0))
                .build();
        setId(slot, id);
        return slot;
    }

    @Nested
    @DisplayName("dispatchDueReminders")
    class DispatchDueReminders {

        @Test
        @DisplayName("正常系: remind_at 到来済み PENDING の配送要求を、解決済みの値を載せて publishOne へ渡す")
        void 配送要求を発行() {
            given(reminderService.findDueReminders()).willReturn(List.of(pendingReminder(1L, RESERVATION_ID)));
            given(reservationRepository.findAllById(any())).willReturn(List.of(reservation(RESERVATION_ID, SLOT_ID, USER_ID)));
            given(slotRepository.findAllById(any())).willReturn(List.of(slot(SLOT_ID)));
            given(reminderRunner.publishOne(any())).willReturn(true);

            batch.dispatchDueReminders();

            ArgumentCaptor<ReservationReminderDispatchEvent> captor =
                    ArgumentCaptor.forClass(ReservationReminderDispatchEvent.class);
            verify(reminderRunner).publishOne(captor.capture());
            ReservationReminderDispatchEvent event = captor.getValue();
            assertThat(event.reminderId()).isEqualTo(1L);
            assertThat(event.reservationId()).isEqualTo(RESERVATION_ID);
            assertThat(event.teamId()).isEqualTo(TEAM_ID);
            assertThat(event.userId()).isEqualTo(USER_ID);
            assertThat(event.slotStartAt()).isEqualTo(LocalDateTime.of(2026, 4, 2, 10, 0));
            assertThat(event.slotTitle()).isEqualTo("整体60分コース");
        }

        @Test
        @DisplayName("正常系: 対象 0 件なら何もしない（一括取得も publish もしない）")
        void 対象なし() {
            given(reminderService.findDueReminders()).willReturn(List.of());

            batch.dispatchDueReminders();

            verifyNoInteractions(reservationRepository, slotRepository, reminderRunner);
        }

        @Test
        @DisplayName("スキップ: 予約が解決できない場合は publish しない（PENDING のまま）")
        void 予約解決不能() {
            given(reminderService.findDueReminders()).willReturn(List.of(pendingReminder(1L, RESERVATION_ID)));
            given(reservationRepository.findAllById(any())).willReturn(List.of());
            given(slotRepository.findAllById(any())).willReturn(List.of());

            batch.dispatchDueReminders();

            verify(reminderRunner, never()).publishOne(any());
        }

        @Test
        @DisplayName("スキップ: 枠が解決できない場合は publish しない（PENDING のまま）")
        void 枠解決不能() {
            given(reminderService.findDueReminders()).willReturn(List.of(pendingReminder(1L, RESERVATION_ID)));
            given(reservationRepository.findAllById(any())).willReturn(List.of(reservation(RESERVATION_ID, SLOT_ID, USER_ID)));
            given(slotRepository.findAllById(any())).willReturn(List.of());

            batch.dispatchDueReminders();

            verify(reminderRunner, never()).publishOne(any());
        }

        @Test
        @DisplayName("1 件の項目TX／投入が失敗しても（例外）、バッチは落ちず後続の行を処理する")
        void 一件の失敗は他の行を巻き込まない() {
            given(reminderService.findDueReminders()).willReturn(List.of(
                    pendingReminder(1L, 101L), pendingReminder(2L, 102L)));
            given(reservationRepository.findAllById(any())).willReturn(List.of(
                    reservation(101L, 201L, 401L), reservation(102L, 202L, 402L)));
            given(slotRepository.findAllById(any())).willReturn(List.of(slot(201L), slot(202L)));
            doThrow(new RuntimeException("項目TX失敗")).when(reminderRunner)
                    .publishOne(org.mockito.ArgumentMatchers.argThat(e -> e != null && e.reminderId() == 1L));

            batch.dispatchDueReminders();

            verify(reminderRunner, times(2)).publishOne(any());
        }

        @Test
        @DisplayName("性能: reservation / slot は件数によらず一括取得を 1 回ずつだけ行う")
        void 一括取得を維持() {
            given(reminderService.findDueReminders()).willReturn(List.of(
                    pendingReminder(1L, 101L), pendingReminder(2L, 102L), pendingReminder(3L, 103L)));
            given(reservationRepository.findAllById(any())).willReturn(List.of(
                    reservation(101L, 201L, 401L), reservation(102L, 202L, 402L), reservation(103L, 203L, 403L)));
            given(slotRepository.findAllById(any())).willReturn(List.of(slot(201L), slot(202L), slot(203L)));

            batch.dispatchDueReminders();

            verify(reservationRepository, times(1)).findAllById(any());
            verify(slotRepository, times(1)).findAllById(any());
            verify(reservationRepository, never()).findById(any());
            verify(slotRepository, never()).findById(any());
            verify(reminderRunner, times(3)).publishOne(any());
        }
    }
}
