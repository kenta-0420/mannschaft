package com.mannschaft.app.schedule;

import com.mannschaft.app.schedule.event.ScheduleCreatedEvent;
import com.mannschaft.app.schedule.repository.ScheduleScheduledTaskRepository;
import com.mannschaft.app.schedule.service.ScheduleAttendanceService;
import com.mannschaft.app.schedule.service.ScheduleAttendanceSolicitationEventListener;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * {@link ScheduleAttendanceSolicitationEventListener} の単体テスト（機能55 第二陣・RSVP 根治）。
 * 予約タスク有無での即時募集の分岐を検証する。
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("ScheduleAttendanceSolicitationEventListener 単体テスト")
class ScheduleAttendanceSolicitationEventListenerTest {

    @Mock
    private ScheduleAttendanceService scheduleAttendanceService;

    @Mock
    private ScheduleScheduledTaskRepository scheduledTaskRepository;

    @InjectMocks
    private ScheduleAttendanceSolicitationEventListener listener;

    private static final Long SCHEDULE_ID = 1L;
    private static final Long TEAM_ID = 10L;
    private static final Long USER_ID = 100L;

    @Test
    @DisplayName("attendanceRequiredfalse_即時募集しない")
    void attendanceRequiredfalse_即時募集しない() {
        // given
        ScheduleCreatedEvent event = new ScheduleCreatedEvent(SCHEDULE_ID, "TEAM", TEAM_ID, USER_ID, false);

        // when
        listener.onScheduleCreated(event);

        // then
        verify(scheduleAttendanceService, never()).openAttendanceSolicitation(anyLong());
    }

    @Test
    @DisplayName("予約ATTENDANCEタスクあり_即時募集せずバッチに委譲")
    void 予約ATTENDANCEタスクあり_即時募集せずバッチに委譲() {
        // given
        ScheduleCreatedEvent event = new ScheduleCreatedEvent(SCHEDULE_ID, "TEAM", TEAM_ID, USER_ID, true);
        given(scheduledTaskRepository.existsByScheduleIdAndTaskTypeAndStatusAndDeletedAtIsNull(
                SCHEDULE_ID, ScheduledTaskType.ATTENDANCE, ScheduledTaskStatus.PENDING))
                .willReturn(true);

        // when
        listener.onScheduleCreated(event);

        // then
        verify(scheduleAttendanceService, never()).openAttendanceSolicitation(anyLong());
    }

    @Test
    @DisplayName("attendanceRequiredtrueかつ予約タスクなし_即時募集する")
    void attendanceRequiredtrueかつ予約タスクなし_即時募集する() {
        // given
        ScheduleCreatedEvent event = new ScheduleCreatedEvent(SCHEDULE_ID, "TEAM", TEAM_ID, USER_ID, true);
        given(scheduledTaskRepository.existsByScheduleIdAndTaskTypeAndStatusAndDeletedAtIsNull(
                SCHEDULE_ID, ScheduledTaskType.ATTENDANCE, ScheduledTaskStatus.PENDING))
                .willReturn(false);

        // when
        listener.onScheduleCreated(event);

        // then
        verify(scheduleAttendanceService).openAttendanceSolicitation(SCHEDULE_ID);
    }
}
