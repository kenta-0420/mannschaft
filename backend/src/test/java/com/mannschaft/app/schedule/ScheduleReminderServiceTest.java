package com.mannschaft.app.schedule;

import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.schedule.dto.CreateReminderRequest;
import com.mannschaft.app.schedule.dto.ReminderResponse;
import com.mannschaft.app.schedule.entity.ScheduleAttendanceEntity;
import com.mannschaft.app.schedule.entity.ScheduleAttendanceReminderEntity;
import com.mannschaft.app.schedule.entity.ScheduleEntity;
import com.mannschaft.app.schedule.event.ReminderNotificationEvent;
import com.mannschaft.app.schedule.repository.ScheduleAttendanceReminderRepository;
import com.mannschaft.app.schedule.repository.ScheduleAttendanceRepository;
import com.mannschaft.app.schedule.repository.ScheduleRepository;
import com.mannschaft.app.schedule.service.ScheduleReminderService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.MessageSource;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * {@link ScheduleReminderService} の単体テスト。
 * リマインダーの作成（相対/絶対）・一覧取得・即時リマインド（通知発火）・実効時刻ベースのバッチ処理を検証する。
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("ScheduleReminderService 単体テスト")
class ScheduleReminderServiceTest {

    @Mock
    private ScheduleAttendanceReminderRepository reminderRepository;

    @Mock
    private ScheduleAttendanceRepository attendanceRepository;

    @Mock
    private ScheduleRepository scheduleRepository;

    @Mock
    private ApplicationEventPublisher eventPublisher;

    @Mock
    private MessageSource messageSource;

    @InjectMocks
    private ScheduleReminderService reminderService;

    // ========================================
    // テスト用定数・ヘルパー
    // ========================================

    private static final Long SCHEDULE_ID = 1L;

    private ScheduleAttendanceReminderEntity createAbsoluteReminderEntity(LocalDateTime remindAt) {
        return ScheduleAttendanceReminderEntity.builder()
                .scheduleId(SCHEDULE_ID)
                .reminderKind(ReminderKind.ABSOLUTE)
                .remindAt(remindAt)
                .isSent(false)
                .build();
    }

    private ScheduleAttendanceReminderEntity createRelativeReminderEntity(int minutesBefore) {
        return ScheduleAttendanceReminderEntity.builder()
                .scheduleId(SCHEDULE_ID)
                .reminderKind(ReminderKind.RELATIVE)
                .remindBeforeMinutes(minutesBefore)
                .isSent(false)
                .build();
    }

    private ScheduleEntity createSchedule(LocalDateTime startAt, boolean attendanceRequired) {
        return ScheduleEntity.builder()
                .teamId(50L)
                .title("テスト予定")
                .startAt(startAt)
                .attendanceRequired(attendanceRequired)
                .build();
    }

    private void stubMessages() {
        given(messageSource.getMessage(anyString(), any(), anyString(), any()))
                .willAnswer(invocation -> invocation.getArgument(2));
    }

    // ========================================
    // createReminders
    // ========================================

    @Nested
    @DisplayName("createReminders")
    class CreateReminders {

        @Test
        @DisplayName("絶対指定_remindAtが保存される")
        void 絶対指定_remindAtが保存される() {
            // given
            given(reminderRepository.countByScheduleId(SCHEDULE_ID)).willReturn(0L);
            given(reminderRepository.save(any(ScheduleAttendanceReminderEntity.class)))
                    .willAnswer(invocation -> invocation.getArgument(0));

            LocalDateTime remindAt = LocalDateTime.now().plusDays(1);
            List<CreateReminderRequest> requests = List.of(
                    new CreateReminderRequest(remindAt, null, ReminderKind.ABSOLUTE));

            // when
            List<ReminderResponse> result = reminderService.createReminders(SCHEDULE_ID, requests);

            // then
            assertThat(result).hasSize(1);
            ArgumentCaptor<ScheduleAttendanceReminderEntity> captor =
                    ArgumentCaptor.forClass(ScheduleAttendanceReminderEntity.class);
            verify(reminderRepository).save(captor.capture());
            assertThat(captor.getValue().getReminderKind()).isEqualTo(ReminderKind.ABSOLUTE);
            assertThat(captor.getValue().getRemindAt()).isEqualTo(remindAt);
            assertThat(captor.getValue().getRemindBeforeMinutes()).isNull();
        }

        @Test
        @DisplayName("相対指定_remindBeforeMinutesが保存されremindAtはnull")
        void 相対指定_remindBeforeMinutesが保存されremindAtはnull() {
            // given
            given(reminderRepository.countByScheduleId(SCHEDULE_ID)).willReturn(0L);
            given(reminderRepository.save(any(ScheduleAttendanceReminderEntity.class)))
                    .willAnswer(invocation -> invocation.getArgument(0));

            List<CreateReminderRequest> requests = List.of(
                    new CreateReminderRequest(null, 30, ReminderKind.RELATIVE));

            // when
            reminderService.createReminders(SCHEDULE_ID, requests);

            // then
            ArgumentCaptor<ScheduleAttendanceReminderEntity> captor =
                    ArgumentCaptor.forClass(ScheduleAttendanceReminderEntity.class);
            verify(reminderRepository).save(captor.capture());
            assertThat(captor.getValue().getReminderKind()).isEqualTo(ReminderKind.RELATIVE);
            assertThat(captor.getValue().getRemindBeforeMinutes()).isEqualTo(30);
            assertThat(captor.getValue().getRemindAt()).isNull();
        }

        @Test
        @DisplayName("リマインダー作成_上限超過_例外スロー")
        void リマインダー作成_上限超過_例外スロー() {
            // given
            given(reminderRepository.countByScheduleId(SCHEDULE_ID)).willReturn(4L);
            List<CreateReminderRequest> requests = List.of(
                    new CreateReminderRequest(LocalDateTime.now().plusDays(1), null, ReminderKind.ABSOLUTE),
                    new CreateReminderRequest(LocalDateTime.now().plusDays(2), null, ReminderKind.ABSOLUTE));

            // when & then
            assertThatThrownBy(() -> reminderService.createReminders(SCHEDULE_ID, requests))
                    .isInstanceOf(BusinessException.class)
                    .extracting(e -> ((BusinessException) e).getErrorCode())
                    .isEqualTo(ScheduleErrorCode.MAX_REMINDERS_EXCEEDED);
        }
    }

    // ========================================
    // sendReminder
    // ========================================

    @Nested
    @DisplayName("sendReminder")
    class SendReminder {

        @Test
        @DisplayName("出欠必須_未回答者あり_通知イベント発火")
        void 出欠必須_未回答者あり_通知イベント発火() {
            // given
            given(scheduleRepository.findById(SCHEDULE_ID))
                    .willReturn(Optional.of(createSchedule(LocalDateTime.now().plusHours(1), true)));
            ScheduleAttendanceEntity undecided = ScheduleAttendanceEntity.builder()
                    .scheduleId(SCHEDULE_ID)
                    .userId(100L)
                    .status(AttendanceStatus.UNDECIDED)
                    .build();
            given(attendanceRepository.findByScheduleIdAndStatus(SCHEDULE_ID, AttendanceStatus.UNDECIDED))
                    .willReturn(List.of(undecided));
            stubMessages();

            // when
            reminderService.sendReminder(SCHEDULE_ID);

            // then
            ArgumentCaptor<ReminderNotificationEvent> captor =
                    ArgumentCaptor.forClass(ReminderNotificationEvent.class);
            verify(eventPublisher).publishEvent(captor.capture());
            assertThat(captor.getValue().getRecipientUserIds()).containsExactly(100L);
            assertThat(captor.getValue().getScheduleId()).isEqualTo(SCHEDULE_ID);
        }

        @Test
        @DisplayName("出欠不要_全出欠対象者へ通知イベント発火")
        void 出欠不要_全出欠対象者へ通知イベント発火() {
            // given
            given(scheduleRepository.findById(SCHEDULE_ID))
                    .willReturn(Optional.of(createSchedule(LocalDateTime.now().plusHours(1), false)));
            given(attendanceRepository.findByScheduleIdOrderByUserIdAsc(SCHEDULE_ID))
                    .willReturn(List.of(
                            ScheduleAttendanceEntity.builder().scheduleId(SCHEDULE_ID).userId(1L).build(),
                            ScheduleAttendanceEntity.builder().scheduleId(SCHEDULE_ID).userId(2L).build()));
            stubMessages();

            // when
            reminderService.sendReminder(SCHEDULE_ID);

            // then
            ArgumentCaptor<ReminderNotificationEvent> captor =
                    ArgumentCaptor.forClass(ReminderNotificationEvent.class);
            verify(eventPublisher).publishEvent(captor.capture());
            assertThat(captor.getValue().getRecipientUserIds()).containsExactly(1L, 2L);
        }

        @Test
        @DisplayName("対象者なし_イベント発火しない")
        void 対象者なし_イベント発火しない() {
            // given
            given(scheduleRepository.findById(SCHEDULE_ID))
                    .willReturn(Optional.of(createSchedule(LocalDateTime.now().plusHours(1), true)));
            given(attendanceRepository.findByScheduleIdAndStatus(SCHEDULE_ID, AttendanceStatus.UNDECIDED))
                    .willReturn(List.of());

            // when
            reminderService.sendReminder(SCHEDULE_ID);

            // then
            verify(eventPublisher, never()).publishEvent(any(ReminderNotificationEvent.class));
        }
    }

    // ========================================
    // processScheduledReminders（実効時刻ベース）
    // ========================================

    @Nested
    @DisplayName("processScheduledReminders")
    class ProcessScheduledReminders {

        @Test
        @DisplayName("ABSOLUTE_due到来_送信済みにマークされる")
        void ABSOLUTE_due到来_送信済みにマークされる() {
            // given: remindAt は過去 → due
            ScheduleAttendanceReminderEntity reminder =
                    createAbsoluteReminderEntity(LocalDateTime.now().minusMinutes(5));
            given(reminderRepository.findByIsSentFalse()).willReturn(List.of(reminder));
            given(scheduleRepository.findById(SCHEDULE_ID))
                    .willReturn(Optional.of(createSchedule(LocalDateTime.now().plusHours(1), true)));
            given(attendanceRepository.findByScheduleIdAndStatus(SCHEDULE_ID, AttendanceStatus.UNDECIDED))
                    .willReturn(List.of());
            stubMessages();

            // when
            reminderService.processScheduledReminders();

            // then
            verify(reminderRepository).save(any(ScheduleAttendanceReminderEntity.class));
            assertThat(reminder.getIsSent()).isTrue();
        }

        @Test
        @DisplayName("RELATIVE_開始N分前到来_送信済みにマークされる")
        void RELATIVE_開始N分前到来_送信済みにマークされる() {
            // given: 開始は5分後・30分前リマインド → 実効時刻は過去 → due
            ScheduleAttendanceReminderEntity reminder = createRelativeReminderEntity(30);
            given(reminderRepository.findByIsSentFalse()).willReturn(List.of(reminder));
            given(scheduleRepository.findById(SCHEDULE_ID))
                    .willReturn(Optional.of(createSchedule(LocalDateTime.now().plusMinutes(5), true)));
            given(attendanceRepository.findByScheduleIdAndStatus(SCHEDULE_ID, AttendanceStatus.UNDECIDED))
                    .willReturn(List.of());
            stubMessages();

            // when
            reminderService.processScheduledReminders();

            // then
            verify(reminderRepository).save(any(ScheduleAttendanceReminderEntity.class));
            assertThat(reminder.getIsSent()).isTrue();
        }

        @Test
        @DisplayName("RELATIVE_開始まで余裕あり_送信されない")
        void RELATIVE_開始まで余裕あり_送信されない() {
            // given: 開始は10時間後・30分前リマインド → 実効時刻は未来 → スキップ
            ScheduleAttendanceReminderEntity reminder = createRelativeReminderEntity(30);
            given(reminderRepository.findByIsSentFalse()).willReturn(List.of(reminder));
            given(scheduleRepository.findById(SCHEDULE_ID))
                    .willReturn(Optional.of(createSchedule(LocalDateTime.now().plusHours(10), true)));

            // when
            reminderService.processScheduledReminders();

            // then
            verify(reminderRepository, never()).save(any(ScheduleAttendanceReminderEntity.class));
            assertThat(reminder.getIsSent()).isFalse();
        }

        @Test
        @DisplayName("未送信なし_何もしない")
        void 未送信なし_何もしない() {
            // given
            given(reminderRepository.findByIsSentFalse()).willReturn(List.of());

            // when
            reminderService.processScheduledReminders();

            // then
            verify(reminderRepository, never()).save(any(ScheduleAttendanceReminderEntity.class));
            verify(eventPublisher, never()).publishEvent(any(ReminderNotificationEvent.class));
        }
    }
}
