package com.mannschaft.app.schedule;

import com.mannschaft.app.schedule.dto.UpdateReminderRequest;
import com.mannschaft.app.schedule.entity.ScheduleAttendanceReminderEntity;
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

import java.util.Collections;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * {@link ScheduleReminderService#updateReminders} の単体テスト。
 *
 * <p>機能55 BE対応: リマインダー更新（既存全削除→再作成）のロジックを検証する。</p>
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("ScheduleReminderService#updateReminders 単体テスト")
class ScheduleReminderServiceUpdateTest {

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

    private static final Long SCHEDULE_ID = 1L;

    @Nested
    @DisplayName("updateReminders")
    class UpdateReminders {

        @Test
        @DisplayName("非空リスト_既存削除後に新規登録される")
        void 非空リスト_削除後に新規登録() {
            // given
            given(reminderRepository.save(any(ScheduleAttendanceReminderEntity.class)))
                    .willAnswer(invocation -> invocation.getArgument(0));

            List<UpdateReminderRequest> newReminders = List.of(
                    new UpdateReminderRequest(null, 30, ReminderKind.RELATIVE));

            // when
            reminderService.updateReminders(SCHEDULE_ID, newReminders);

            // then: 先に全削除してから新規登録
            verify(reminderRepository).deleteByScheduleId(SCHEDULE_ID);
            ArgumentCaptor<ScheduleAttendanceReminderEntity> captor =
                    ArgumentCaptor.forClass(ScheduleAttendanceReminderEntity.class);
            verify(reminderRepository).save(captor.capture());
            assertThat(captor.getValue().getReminderKind()).isEqualTo(ReminderKind.RELATIVE);
            assertThat(captor.getValue().getRemindBeforeMinutes()).isEqualTo(30);
        }

        @Test
        @DisplayName("空リスト_既存削除のみ（新規登録なし）")
        void 空リスト_削除のみ() {
            // when
            reminderService.updateReminders(SCHEDULE_ID, Collections.emptyList());

            // then: 削除は実行、登録はなし
            verify(reminderRepository).deleteByScheduleId(SCHEDULE_ID);
            verify(reminderRepository, never()).save(any());
            verify(reminderRepository, never()).countByScheduleId(any());
        }

        @Test
        @DisplayName("5件_上限いっぱい_違反なし_全件保存される")
        void 上限5件_全件保存() {
            // given
            given(reminderRepository.save(any(ScheduleAttendanceReminderEntity.class)))
                    .willAnswer(invocation -> invocation.getArgument(0));

            List<UpdateReminderRequest> reminders = List.of(
                    new UpdateReminderRequest(null, 10, ReminderKind.RELATIVE),
                    new UpdateReminderRequest(null, 20, ReminderKind.RELATIVE),
                    new UpdateReminderRequest(null, 30, ReminderKind.RELATIVE),
                    new UpdateReminderRequest(null, 60, ReminderKind.RELATIVE),
                    new UpdateReminderRequest(null, 120, ReminderKind.RELATIVE));

            // when
            reminderService.updateReminders(SCHEDULE_ID, reminders);

            // then
            verify(reminderRepository).deleteByScheduleId(SCHEDULE_ID);
            // 5回 save が呼ばれる
            verify(reminderRepository, org.mockito.Mockito.times(5))
                    .save(any(ScheduleAttendanceReminderEntity.class));
        }

        @Test
        @DisplayName("削除後に登録される順序_deleteが先_saveが後")
        void 削除が先_登録が後() {
            // given
            given(reminderRepository.save(any(ScheduleAttendanceReminderEntity.class)))
                    .willAnswer(invocation -> invocation.getArgument(0));

            List<UpdateReminderRequest> reminders = List.of(
                    new UpdateReminderRequest(null, 15, ReminderKind.RELATIVE));

            // then: 呼び出し順序を InOrder で検証
            var inOrder = org.mockito.Mockito.inOrder(reminderRepository);

            // when
            reminderService.updateReminders(SCHEDULE_ID, reminders);

            // then
            inOrder.verify(reminderRepository).deleteByScheduleId(SCHEDULE_ID);
            inOrder.verify(reminderRepository).save(any(ScheduleAttendanceReminderEntity.class));
        }
    }
}
