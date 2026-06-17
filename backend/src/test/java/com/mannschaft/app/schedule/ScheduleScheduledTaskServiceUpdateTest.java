package com.mannschaft.app.schedule;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.mannschaft.app.schedule.dto.ScheduledAttendanceRequest;
import com.mannschaft.app.schedule.dto.ScheduledSurveyRequest;
import com.mannschaft.app.schedule.entity.ScheduleScheduledTaskEntity;
import com.mannschaft.app.schedule.repository.ScheduleScheduledTaskRepository;
import com.mannschaft.app.schedule.service.ScheduleScheduledTaskService;
import com.mannschaft.app.survey.dto.CreateSurveyRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

/**
 * {@link ScheduleScheduledTaskService#updateTasksForSchedule} の単体テスト。
 *
 * <p>機能55 BE対応: 予定編集時の予約タスク差分更新（既存PENDING cancel → 新規登録）を検証する。</p>
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("ScheduleScheduledTaskService#updateTasksForSchedule 単体テスト")
class ScheduleScheduledTaskServiceUpdateTest {

    @Mock
    private ScheduleScheduledTaskRepository scheduledTaskRepository;

    private final ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();

    private ScheduleScheduledTaskService service;

    private static final Long SCHEDULE_ID = 1L;
    private static final Long SCOPE_ID = 10L;
    private static final Long ORG_ID = 100L;
    private static final Long UPDATED_BY = 999L;
    /** JST+09:00 の翌日（DTO 渡し用）。 */
    private static final OffsetDateTime FUTURE = OffsetDateTime.now(ZoneOffset.ofHours(9)).plusDays(1);
    /** FUTURE を Asia/Tokyo に変換した LocalDateTime（Entity builder 渡し用）。 */
    private static final LocalDateTime FUTURE_JST = FUTURE.atZoneSameInstant(ZoneId.of("Asia/Tokyo")).toLocalDateTime();

    @BeforeEach
    void setUp() {
        service = new ScheduleScheduledTaskService(scheduledTaskRepository, objectMapper);
    }

    private ScheduleScheduledTaskEntity pendingSurveyTask() {
        return ScheduleScheduledTaskEntity.builder()
                .scheduleId(SCHEDULE_ID).organizationId(ORG_ID)
                .scopeType(CalendarSyncScopeType.TEAM).scopeId(SCOPE_ID)
                .taskType(ScheduledTaskType.SURVEY).scheduledAt(FUTURE_JST)
                .status(ScheduledTaskStatus.PENDING).payloadJson("{}").build();
    }

    private ScheduleScheduledTaskEntity pendingAttendanceTask() {
        return ScheduleScheduledTaskEntity.builder()
                .scheduleId(SCHEDULE_ID).organizationId(ORG_ID)
                .scopeType(CalendarSyncScopeType.TEAM).scopeId(SCOPE_ID)
                .taskType(ScheduledTaskType.ATTENDANCE).scheduledAt(FUTURE_JST)
                .status(ScheduledTaskStatus.PENDING).payloadJson("{}").build();
    }

    private ScheduleScheduledTaskEntity createdSurveyTask() {
        return ScheduleScheduledTaskEntity.builder()
                .scheduleId(SCHEDULE_ID).organizationId(ORG_ID)
                .scopeType(CalendarSyncScopeType.TEAM).scopeId(SCOPE_ID)
                .taskType(ScheduledTaskType.SURVEY).scheduledAt(FUTURE_JST)
                .status(ScheduledTaskStatus.CREATED).payloadJson("{}").build();
    }

    private CreateSurveyRequest createSurveyRequest() {
        return new CreateSurveyRequest(
                "テストアンケート", "説明",
                false, false,
                "AFTER_CLOSE", "ALL",
                null, null, null, null, null, null,
                null, null, null, null);
    }

    @Nested
    @DisplayName("updateTasksForSchedule")
    class UpdateTasksForSchedule {

        @Test
        @DisplayName("surveys非null_既存PENDINGをCANCELLして新規SURVEYタスクを登録")
        void surveys非null_PENDING取消後に新規登録() {
            // given
            ScheduleScheduledTaskEntity existing = pendingSurveyTask();
            given(scheduledTaskRepository.findByScheduleIdAndDeletedAtIsNull(SCHEDULE_ID))
                    .willReturn(List.of(existing));

            ScheduledSurveyRequest newSurvey = new ScheduledSurveyRequest(FUTURE, createSurveyRequest());

            // when
            service.updateTasksForSchedule(SCHEDULE_ID, CalendarSyncScopeType.TEAM, SCOPE_ID, ORG_ID,
                    UPDATED_BY, List.of(newSurvey), null);

            // then: 既存PENDING → CANCELLED
            assertThat(existing.getStatus()).isEqualTo(ScheduledTaskStatus.CANCELLED);
            // 新規登録（既存cancel=1回 + 新規survey=1回）
            ArgumentCaptor<ScheduleScheduledTaskEntity> captor =
                    ArgumentCaptor.forClass(ScheduleScheduledTaskEntity.class);
            verify(scheduledTaskRepository, times(2)).save(captor.capture());
            var savedEntities = captor.getAllValues();
            // 2回目の save が新規タスク
            ScheduleScheduledTaskEntity newTask = savedEntities.stream()
                    .filter(t -> t.getStatus() == ScheduledTaskStatus.PENDING)
                    .findFirst().orElseThrow();
            assertThat(newTask.getTaskType()).isEqualTo(ScheduledTaskType.SURVEY);
            assertThat(newTask.getScheduleId()).isEqualTo(SCHEDULE_ID);
        }

        @Test
        @DisplayName("attendance非null_既存PENDINGをCANCELLして新規ATTENDANCEタスクを登録")
        void attendance非null_PENDING取消後に新規登録() {
            // given
            ScheduleScheduledTaskEntity existing = pendingAttendanceTask();
            given(scheduledTaskRepository.findByScheduleIdAndDeletedAtIsNull(SCHEDULE_ID))
                    .willReturn(List.of(existing));

            ScheduledAttendanceRequest newAttendance = new ScheduledAttendanceRequest(
                    FUTURE, FUTURE_JST.plusDays(2), "REQUIRED", "MEMBER_PLUS");

            // when
            service.updateTasksForSchedule(SCHEDULE_ID, CalendarSyncScopeType.TEAM, SCOPE_ID, ORG_ID,
                    UPDATED_BY, null, newAttendance);

            // then
            assertThat(existing.getStatus()).isEqualTo(ScheduledTaskStatus.CANCELLED);
            // 既存cancel=1回 + 新規attendance=1回
            verify(scheduledTaskRepository, times(2)).save(any());
        }

        @Test
        @DisplayName("surveys=null_attendance=null_どちらも処理されない")
        void 両方null_処理されない() {
            // when
            service.updateTasksForSchedule(SCHEDULE_ID, CalendarSyncScopeType.TEAM, SCOPE_ID, ORG_ID,
                    UPDATED_BY, null, null);

            // then: findも呼ばれないし saveも呼ばれない
            verify(scheduledTaskRepository, never()).findByScheduleIdAndDeletedAtIsNull(any());
            verify(scheduledTaskRepository, never()).save(any());
        }

        @Test
        @DisplayName("既存CREATED済みタスクはCANCELLされない")
        void CREATED済みタスクはCANCELLされない() {
            // given: CREATED(materialize済み)と PENDING が混在
            ScheduleScheduledTaskEntity created = createdSurveyTask();
            ScheduleScheduledTaskEntity pending = pendingSurveyTask();
            given(scheduledTaskRepository.findByScheduleIdAndDeletedAtIsNull(SCHEDULE_ID))
                    .willReturn(List.of(created, pending));

            ScheduledSurveyRequest newSurvey = new ScheduledSurveyRequest(FUTURE, createSurveyRequest());

            // when
            service.updateTasksForSchedule(SCHEDULE_ID, CalendarSyncScopeType.TEAM, SCOPE_ID, ORG_ID,
                    UPDATED_BY, List.of(newSurvey), null);

            // then: PENDING のみ CANCELLED、CREATED は不変
            assertThat(created.getStatus()).isEqualTo(ScheduledTaskStatus.CREATED);
            assertThat(pending.getStatus()).isEqualTo(ScheduledTaskStatus.CANCELLED);
        }

        @Test
        @DisplayName("既存タスクなし_新規タスクのみ登録される")
        void 既存なし_新規登録のみ() {
            // given
            given(scheduledTaskRepository.findByScheduleIdAndDeletedAtIsNull(SCHEDULE_ID))
                    .willReturn(List.of());

            ScheduledSurveyRequest newSurvey = new ScheduledSurveyRequest(FUTURE, createSurveyRequest());

            // when
            service.updateTasksForSchedule(SCHEDULE_ID, CalendarSyncScopeType.TEAM, SCOPE_ID, ORG_ID,
                    UPDATED_BY, List.of(newSurvey), null);

            // then: 新規SURVEYタスクが1件登録
            ArgumentCaptor<ScheduleScheduledTaskEntity> captor =
                    ArgumentCaptor.forClass(ScheduleScheduledTaskEntity.class);
            verify(scheduledTaskRepository, times(1)).save(captor.capture());
            assertThat(captor.getValue().getStatus()).isEqualTo(ScheduledTaskStatus.PENDING);
            assertThat(captor.getValue().getTaskType()).isEqualTo(ScheduledTaskType.SURVEY);
        }

        @Test
        @DisplayName("surveys空リスト_既存PENDINGをCANCELLし新規登録なし")
        void surveys空リスト_既存PENDING取消のみ() {
            // given
            ScheduleScheduledTaskEntity pending = pendingSurveyTask();
            given(scheduledTaskRepository.findByScheduleIdAndDeletedAtIsNull(SCHEDULE_ID))
                    .willReturn(List.of(pending));

            // when: 空リスト（全削除の意）
            service.updateTasksForSchedule(SCHEDULE_ID, CalendarSyncScopeType.TEAM, SCOPE_ID, ORG_ID,
                    UPDATED_BY, List.of(), null);

            // then: 既存PENDING取消のみ、新規登録なし
            assertThat(pending.getStatus()).isEqualTo(ScheduledTaskStatus.CANCELLED);
            // save は取消の1回のみ
            verify(scheduledTaskRepository, times(1)).save(pending);
        }
    }
}
