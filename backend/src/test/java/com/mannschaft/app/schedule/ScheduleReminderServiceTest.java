package com.mannschaft.app.schedule;

import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.schedule.dto.CreateReminderRequest;
import com.mannschaft.app.schedule.dto.ReminderResponse;
import com.mannschaft.app.schedule.entity.ScheduleAttendanceEntity;
import com.mannschaft.app.schedule.entity.ScheduleAttendanceReminderEntity;
import com.mannschaft.app.schedule.repository.ScheduleAttendanceReminderRepository;
import com.mannschaft.app.schedule.repository.ScheduleAttendanceRepository;
import com.mannschaft.app.schedule.service.ScheduleReminderService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * {@link ScheduleReminderService} の単体テスト。
 * リマインダーの作成・一覧取得・即時リマインド・バッチ処理を検証する。
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("ScheduleReminderService 単体テスト")
class ScheduleReminderServiceTest {

    @Mock
    private ScheduleAttendanceReminderRepository reminderRepository;

    @Mock
    private ScheduleAttendanceRepository attendanceRepository;

    @InjectMocks
    private ScheduleReminderService reminderService;

    // ========================================
    // テスト用定数・ヘルパー
    // ========================================

    private static final Long SCHEDULE_ID = 1L;

    private ScheduleAttendanceReminderEntity createReminderEntity() {
        return ScheduleAttendanceReminderEntity.builder()
                .scheduleId(SCHEDULE_ID)
                .remindAt(LocalDateTime.of(2026, 4, 1, 10, 0))
                .isSent(false)
                .build();
    }

    // ========================================
    // createReminders
    // ========================================

    @Nested
    @DisplayName("createReminders")
    class CreateReminders {

        @Test
        @DisplayName("リマインダー作成_正常_一覧を返す")
        void リマインダー作成_正常_一覧を返す() {
            // given
            given(reminderRepository.countByScheduleId(SCHEDULE_ID)).willReturn(0L);
            given(reminderRepository.save(any(ScheduleAttendanceReminderEntity.class)))
                    .willAnswer(invocation -> {
                        ScheduleAttendanceReminderEntity e = invocation.getArgument(0);
                        java.lang.reflect.Method m =
                                ScheduleAttendanceReminderEntity.class.getDeclaredMethod("onCreate");
                        m.setAccessible(true);
                        m.invoke(e);
                        return e;
                    });

            List<CreateReminderRequest> requests = List.of(
                    new CreateReminderRequest(LocalDateTime.of(2026, 4, 1, 10, 0)));

            // when
            List<ReminderResponse> result = reminderService.createReminders(SCHEDULE_ID, requests);

            // then
            assertThat(result).hasSize(1);
            verify(reminderRepository).save(any(ScheduleAttendanceReminderEntity.class));
        }

        @Test
        @DisplayName("リマインダー作成_上限超過_例外スロー")
        void リマインダー作成_上限超過_例外スロー() {
            // given
            given(reminderRepository.countByScheduleId(SCHEDULE_ID)).willReturn(4L);
            List<CreateReminderRequest> requests = List.of(
                    new CreateReminderRequest(LocalDateTime.of(2026, 4, 1, 10, 0)),
                    new CreateReminderRequest(LocalDateTime.of(2026, 4, 2, 10, 0)));

            // when & then
            assertThatThrownBy(() -> reminderService.createReminders(SCHEDULE_ID, requests))
                    .isInstanceOf(BusinessException.class)
                    .extracting(e -> ((BusinessException) e).getErrorCode())
                    .isEqualTo(ScheduleErrorCode.MAX_REMINDERS_EXCEEDED);
        }
    }

    // ========================================
    // getReminders
    // ========================================

    @Nested
    @DisplayName("getReminders")
    class GetReminders {

        @Test
        @DisplayName("リマインダー取得_正常_一覧を返す")
        void リマインダー取得_正常_一覧を返す() {
            // given
            ScheduleAttendanceReminderEntity entity = createReminderEntity();
            given(reminderRepository.findByScheduleIdOrderByRemindAtAsc(SCHEDULE_ID))
                    .willReturn(List.of(entity));

            // when
            List<ReminderResponse> result = reminderService.getReminders(SCHEDULE_ID);

            // then
            assertThat(result).hasSize(1);
            assertThat(result.get(0).getIsSent()).isFalse();
        }
    }

    // ========================================
    // sendReminder
    // ========================================

    @Nested
    @DisplayName("sendReminder")
    class SendReminder {

        @Test
        @DisplayName("即時リマインド_未回答者あり_処理される")
        void 即時リマインド_未回答者あり_処理される() {
            // given
            ScheduleAttendanceEntity undecided = ScheduleAttendanceEntity.builder()
                    .scheduleId(SCHEDULE_ID)
                    .userId(100L)
                    .status(AttendanceStatus.UNDECIDED)
                    .build();
            given(attendanceRepository.findByScheduleIdAndStatus(SCHEDULE_ID, AttendanceStatus.UNDECIDED))
                    .willReturn(List.of(undecided));

            // when
            reminderService.sendReminder(SCHEDULE_ID);

            // then（ログ出力で確認。通知機能未実装のため副作用なし）
        }

        @Test
        @DisplayName("即時リマインド_未回答者なし_何もしない")
        void 即時リマインド_未回答者なし_何もしない() {
            // given
            given(attendanceRepository.findByScheduleIdAndStatus(SCHEDULE_ID, AttendanceStatus.UNDECIDED))
                    .willReturn(List.of());

            // when
            reminderService.sendReminder(SCHEDULE_ID);

            // then（例外なく正常終了）
        }
    }

    // ========================================
    // processScheduledReminders
    // ========================================

    @Nested
    @DisplayName("processScheduledReminders")
    class ProcessScheduledReminders {

        @Test
        @DisplayName("バッチ処理_未送信あり_送信済みにマークされる")
        void バッチ処理_未送信あり_送信済みにマークされる() {
            // given
            ScheduleAttendanceReminderEntity reminder = createReminderEntity();
            given(reminderRepository.findByIsSentFalseAndRemindAtBeforeOrderByRemindAtAsc(any(LocalDateTime.class)))
                    .willReturn(List.of(reminder));
            given(attendanceRepository.findByScheduleIdAndStatus(SCHEDULE_ID, AttendanceStatus.UNDECIDED))
                    .willReturn(List.of());

            // when
            reminderService.processScheduledReminders();

            // then
            verify(reminderRepository).save(any(ScheduleAttendanceReminderEntity.class));
            assertThat(reminder.getIsSent()).isTrue();
        }

        @Test
        @DisplayName("バッチ処理_未送信なし_何もしない")
        void バッチ処理_未送信なし_何もしない() {
            // given
            given(reminderRepository.findByIsSentFalseAndRemindAtBeforeOrderByRemindAtAsc(any(LocalDateTime.class)))
                    .willReturn(List.of());

            // when
            reminderService.processScheduledReminders();

            // then
            verify(reminderRepository, never()).save(any(ScheduleAttendanceReminderEntity.class));
        }
    }
}
