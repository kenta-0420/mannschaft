package com.mannschaft.app.reservation.service;

import com.mannschaft.app.notification.NotificationScopeType;
import com.mannschaft.app.common.i18n.UserLocaleCache;
import com.mannschaft.app.notification.service.NotificationHelper;
import com.mannschaft.app.reservation.ReminderStatus;
import com.mannschaft.app.reservation.entity.ReservationEntity;
import com.mannschaft.app.reservation.entity.ReservationReminderEntity;
import com.mannschaft.app.reservation.entity.ReservationSlotEntity;
import com.mannschaft.app.reservation.repository.ReservationReminderRepository;
import com.mannschaft.app.reservation.repository.ReservationRepository;
import com.mannschaft.app.reservation.repository.ReservationSlotRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import org.springframework.context.MessageSource;

import java.lang.reflect.Field;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.List;
import java.util.Locale;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * {@link ReservationReminderDispatchBatchService} の単体テスト（F03.4 段階拡張 ⑥ リマインド実送信）。
 *
 * <p>remind_at 到来済み PENDING の送出 → {@link NotificationHelper#notify} 呼出 ＋ SENT 遷移、
 * 解決不能（予約/枠なし）のスキップ、送出失敗時に SENT へ遷移しない（リトライ余地）を番人化する。</p>
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("ReservationReminderDispatchBatchService 単体テスト")
class ReservationReminderDispatchBatchServiceTest {

    @Mock
    private ReservationReminderService reminderService;

    @Mock
    private ReservationReminderRepository reminderRepository;

    @Mock
    private ReservationRepository reservationRepository;

    @Mock
    private ReservationSlotRepository slotRepository;

    @Mock
    private NotificationHelper notificationHelper;

    /**
     * 通知本文の i18n 化（Issue #2543 申し送り）で増えた依存。
     * スタブしないと {@code getLocale} が null を返し
     * {@code Locale.forLanguageTag(null)} が NPE となる。その NPE は
     * 行単位の try/catch に捕まるため通知が飛ばず「zero interactions」で落ちる。
     */
    @Mock
    private UserLocaleCache userLocaleCache;

    /** 同上。文面は MessageSource キーから引くようになった。 */
    @Mock
    private MessageSource messageSource;

    @InjectMocks
    private ReservationReminderDispatchBatchService batch;

    private static final Long RESERVATION_ID = 100L;
    private static final Long SLOT_ID = 200L;
    private static final Long TEAM_ID = 300L;
    private static final Long USER_ID = 400L;

    // ========================================
    // テスト用ヘルパー（id は IDENTITY 採番のためリフレクションで設定）
    // ========================================

    /**
     * 通知本文の i18n 化に伴う依存をスタブする（本文の中身は本テストの検証対象ではない）。
     *
     * <p>スタブしないと {@code getLocale} が null を返し {@code Locale.forLanguageTag(null)} が
     * NPE になる。その NPE は送出ループの行単位 try/catch に捕まるため通知が飛ばず、
     * 「Wanted but not invoked / zero interactions」という一見無関係な失敗になる。
     * 本文の言語切替そのものは {@code ReservationWaitlistServiceTest} で番人化している。</p>
     */
    private void givenI18n() {
        given(userLocaleCache.getLocale(anyLong())).willReturn("ja");
        given(messageSource.getMessage(any(String.class), any(), any(String.class), any(Locale.class)))
                .willReturn("dummy");
    }

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

    private ReservationReminderEntity pendingReminder() {
        ReservationReminderEntity reminder = ReservationReminderEntity.builder()
                .reservationId(RESERVATION_ID)
                .remindAt(LocalDateTime.of(2026, 4, 1, 9, 0))
                .build();
        setId(reminder, 1L);
        return reminder;
    }

    private ReservationEntity reservation() {
        ReservationEntity reservation = ReservationEntity.builder()
                .reservationSlotId(SLOT_ID)
                .lineId(1L)
                .teamId(TEAM_ID)
                .userId(USER_ID)
                .build();
        setId(reservation, RESERVATION_ID);
        return reservation;
    }

    private ReservationSlotEntity slot() {
        ReservationSlotEntity slot = ReservationSlotEntity.builder()
                .teamId(TEAM_ID)
                .title("整体60分コース")
                .slotDate(LocalDate.of(2026, 4, 2))
                .startTime(LocalTime.of(10, 0))
                .endTime(LocalTime.of(11, 0))
                .build();
        setId(slot, SLOT_ID);
        return slot;
    }

    // ========================================
    // 正常系
    // ========================================

    @Nested
    @DisplayName("dispatchDueReminders")
    class DispatchDueReminders {

        @Test
        @DisplayName("正常系: remind_at 到来済み PENDING を送出し SENT にマークする")
        void 送出_正常() {
            ReservationReminderEntity reminder = pendingReminder();
            given(reminderService.findDueReminders()).willReturn(List.of(reminder));
            given(reservationRepository.findAllById(any())).willReturn(List.of(reservation()));
            given(slotRepository.findAllById(any())).willReturn(List.of(slot()));
            givenI18n();

            batch.dispatchDueReminders();

            // 通知送出（予約者本人・RESERVATION_REMINDER・TEAM スコープ・actorId=null）
            verify(notificationHelper).notify(
                    eq(USER_ID),
                    eq("RESERVATION_REMINDER"),
                    any(String.class),
                    any(String.class),
                    eq("RESERVATION"),
                    eq(RESERVATION_ID),
                    eq(NotificationScopeType.TEAM),
                    eq(TEAM_ID),
                    eq("/teams/" + TEAM_ID + "/reservations"),
                    eq(null));

            // 送信成功 → SENT ＋ sentAt
            assertThat(reminder.getStatus()).isEqualTo(ReminderStatus.SENT);
            assertThat(reminder.getSentAt()).isNotNull();
            verify(reminderRepository).save(reminder);
        }

        @Test
        @DisplayName("正常系: 対象 0 件なら何もしない（送出・保存なし）")
        void 送出_対象なし() {
            given(reminderService.findDueReminders()).willReturn(List.of());

            batch.dispatchDueReminders();

            verify(notificationHelper, never()).notify(
                    anyLong(), any(), any(), any(), any(), any(), any(), any(), any(), any());
            verify(reminderRepository, never()).save(any());
        }

        @Test
        @DisplayName("スキップ: 予約が解決できない場合は送出せず SENT にしない")
        void 送出_予約解決不能() {
            ReservationReminderEntity reminder = pendingReminder();
            given(reminderService.findDueReminders()).willReturn(List.of(reminder));
            given(reservationRepository.findAllById(any())).willReturn(List.of()); // 予約なし
            given(slotRepository.findAllById(any())).willReturn(List.of());

            batch.dispatchDueReminders();

            verify(notificationHelper, never()).notify(
                    anyLong(), any(), any(), any(), any(), any(), any(), any(), any(), any());
            assertThat(reminder.getStatus()).isEqualTo(ReminderStatus.PENDING);
            verify(reminderRepository, never()).save(any());
        }

        @Test
        @DisplayName("スキップ: 枠が解決できない場合は送出せず SENT にしない")
        void 送出_枠解決不能() {
            ReservationReminderEntity reminder = pendingReminder();
            given(reminderService.findDueReminders()).willReturn(List.of(reminder));
            given(reservationRepository.findAllById(any())).willReturn(List.of(reservation()));
            given(slotRepository.findAllById(any())).willReturn(List.of()); // 枠なし

            batch.dispatchDueReminders();

            verify(notificationHelper, never()).notify(
                    anyLong(), any(), any(), any(), any(), any(), any(), any(), any(), any());
            assertThat(reminder.getStatus()).isEqualTo(ReminderStatus.PENDING);
            verify(reminderRepository, never()).save(any());
        }

        @Test
        @DisplayName("二重送信回避: 送出失敗時は SENT にせず保存しない（次回再送の余地）")
        void 送出_失敗時はSENTにしない() {
            ReservationReminderEntity reminder = pendingReminder();
            given(reminderService.findDueReminders()).willReturn(List.of(reminder));
            given(reservationRepository.findAllById(any())).willReturn(List.of(reservation()));
            given(slotRepository.findAllById(any())).willReturn(List.of(slot()));
            givenI18n();
            doThrow(new RuntimeException("dispatch failed")).when(notificationHelper).notify(
                    anyLong(), any(), any(), any(), any(), any(), any(), any(), any(), any());

            batch.dispatchDueReminders();

            // 送信失敗 → SENT にしない（PENDING のまま）・保存もしない
            assertThat(reminder.getStatus()).isEqualTo(ReminderStatus.PENDING);
            assertThat(reminder.getSentAt()).isNull();
            verify(reminderRepository, never()).save(any());
        }
    }
}
