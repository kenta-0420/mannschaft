package com.mannschaft.app.schedule;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.mannschaft.app.schedule.dto.ScheduledAttendanceRequest;
import com.mannschaft.app.schedule.dto.ScheduledSurveyRequest;
import com.mannschaft.app.schedule.entity.ScheduleScheduledTaskEntity;
import com.mannschaft.app.schedule.repository.ScheduleScheduledTaskRepository;
import com.mannschaft.app.schedule.service.ScheduleScheduledTaskService;
import com.mannschaft.app.survey.dto.CreateSurveyRequest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

/**
 * {@link ScheduleScheduledTaskService} の単体テスト（機能55 第二陣）。
 * 予約タスクの登録・JSON 直列化・取消・取得を検証する。
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("ScheduleScheduledTaskService 単体テスト")
class ScheduleScheduledTaskServiceTest {

    @Mock
    private ScheduleScheduledTaskRepository scheduledTaskRepository;

    // 実 ObjectMapper を使い JSON 直列化の中身を検証する（本番同等に全モジュール登録）
    private final ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();

    @Captor
    private ArgumentCaptor<ScheduleScheduledTaskEntity> taskCaptor;

    private ScheduleScheduledTaskService service;

    private static final Long SCHEDULE_ID = 1L;
    private static final Long SCOPE_ID = 10L;
    private static final Long ORG_ID = 100L;
    private static final Long CREATED_BY = 999L;
    private static final LocalDateTime FUTURE = LocalDateTime.now().plusDays(1);

    @org.junit.jupiter.api.BeforeEach
    void setUp() {
        service = new ScheduleScheduledTaskService(scheduledTaskRepository, objectMapper);
    }

    private CreateSurveyRequest createSurveyRequest() {
        return new CreateSurveyRequest(
                "出欠アンケート", "説明",
                false, false,
                "AFTER_CLOSE", "ALL",
                null, null, null, null, null, null,
                null, null, null);
    }

    @Nested
    @DisplayName("registerTasks")
    class RegisterTasks {

        @Test
        @DisplayName("予約アンケート_PENDINGで保存されpayloadがJSON直列化される")
        void 予約アンケート_PENDINGで保存されpayloadがJSON直列化される() {
            // given
            ScheduledSurveyRequest surveyReq = new ScheduledSurveyRequest(FUTURE, createSurveyRequest());

            // when
            service.registerTasks(SCHEDULE_ID, CalendarSyncScopeType.TEAM, SCOPE_ID, ORG_ID, CREATED_BY,
                    List.of(surveyReq), null);

            // then
            verify(scheduledTaskRepository).save(taskCaptor.capture());
            ScheduleScheduledTaskEntity saved = taskCaptor.getValue();
            assertThat(saved.getTaskType()).isEqualTo(ScheduledTaskType.SURVEY);
            assertThat(saved.getStatus()).isEqualTo(ScheduledTaskStatus.PENDING);
            assertThat(saved.getScheduleId()).isEqualTo(SCHEDULE_ID);
            assertThat(saved.getOrganizationId()).isEqualTo(ORG_ID);
            assertThat(saved.getScopeType()).isEqualTo(CalendarSyncScopeType.TEAM);
            assertThat(saved.getScopeId()).isEqualTo(SCOPE_ID);
            assertThat(saved.getScheduledAt()).isEqualTo(FUTURE);
            assertThat(saved.getCreatedBy()).isEqualTo(CREATED_BY);
            // payload にアンケートタイトルが JSON として含まれる
            assertThat(saved.getPayloadJson()).contains("出欠アンケート");
        }

        @Test
        @DisplayName("予約出欠募集_PENDINGで保存され出欠設定がpayloadに入る")
        void 予約出欠募集_PENDINGで保存され出欠設定がpayloadに入る() {
            // given
            ScheduledAttendanceRequest attendanceReq = new ScheduledAttendanceRequest(
                    FUTURE, FUTURE.plusDays(2), "REQUIRED", "MEMBER_PLUS");

            // when
            service.registerTasks(SCHEDULE_ID, CalendarSyncScopeType.ORGANIZATION, SCOPE_ID, ORG_ID, CREATED_BY,
                    null, attendanceReq);

            // then
            verify(scheduledTaskRepository).save(taskCaptor.capture());
            ScheduleScheduledTaskEntity saved = taskCaptor.getValue();
            assertThat(saved.getTaskType()).isEqualTo(ScheduledTaskType.ATTENDANCE);
            assertThat(saved.getStatus()).isEqualTo(ScheduledTaskStatus.PENDING);
            assertThat(saved.getScheduledAt()).isEqualTo(FUTURE);
            assertThat(saved.getPayloadJson()).contains("REQUIRED");
        }

        @Test
        @DisplayName("複数アンケート_件数分saveされる")
        void 複数アンケート_件数分saveされる() {
            // given
            ScheduledSurveyRequest s1 = new ScheduledSurveyRequest(FUTURE, createSurveyRequest());
            ScheduledSurveyRequest s2 = new ScheduledSurveyRequest(FUTURE.plusHours(1), createSurveyRequest());

            // when
            service.registerTasks(SCHEDULE_ID, CalendarSyncScopeType.TEAM, SCOPE_ID, ORG_ID, CREATED_BY,
                    List.of(s1, s2), null);

            // then
            verify(scheduledTaskRepository, times(2)).save(any());
        }

        @Test
        @DisplayName("両方null_saveされない")
        void 両方null_saveされない() {
            // when
            service.registerTasks(SCHEDULE_ID, CalendarSyncScopeType.TEAM, SCOPE_ID, ORG_ID, CREATED_BY,
                    null, null);

            // then
            verify(scheduledTaskRepository, never()).save(any());
        }
    }

    @Nested
    @DisplayName("cancelTasksForSchedule")
    class CancelTasksForSchedule {

        @Test
        @DisplayName("PENDINGタスクのみCANCELLEDになる")
        void PENDINGタスクのみCANCELLEDになる() {
            // given
            ScheduleScheduledTaskEntity pending = ScheduleScheduledTaskEntity.builder()
                    .scheduleId(SCHEDULE_ID).organizationId(ORG_ID)
                    .scopeType(CalendarSyncScopeType.TEAM).scopeId(SCOPE_ID)
                    .taskType(ScheduledTaskType.SURVEY).scheduledAt(FUTURE)
                    .status(ScheduledTaskStatus.PENDING).payloadJson("{}").build();
            ScheduleScheduledTaskEntity alreadyCreated = ScheduleScheduledTaskEntity.builder()
                    .scheduleId(SCHEDULE_ID).organizationId(ORG_ID)
                    .scopeType(CalendarSyncScopeType.TEAM).scopeId(SCOPE_ID)
                    .taskType(ScheduledTaskType.ATTENDANCE).scheduledAt(FUTURE)
                    .status(ScheduledTaskStatus.CREATED).payloadJson("{}").build();
            given(scheduledTaskRepository.findByScheduleIdAndDeletedAtIsNull(SCHEDULE_ID))
                    .willReturn(List.of(pending, alreadyCreated));

            // when
            service.cancelTasksForSchedule(SCHEDULE_ID);

            // then
            assertThat(pending.getStatus()).isEqualTo(ScheduledTaskStatus.CANCELLED);
            assertThat(alreadyCreated.getStatus()).isEqualTo(ScheduledTaskStatus.CREATED);
            verify(scheduledTaskRepository, times(1)).save(pending);
            verify(scheduledTaskRepository, never()).save(alreadyCreated);
        }
    }

    @Nested
    @DisplayName("findTasksForSchedule")
    class FindTasksForSchedule {

        @Test
        @DisplayName("予定に紐づくタスク一覧を返す")
        void 予定に紐づくタスク一覧を返す() {
            // given
            ScheduleScheduledTaskEntity task = ScheduleScheduledTaskEntity.builder()
                    .scheduleId(SCHEDULE_ID).organizationId(ORG_ID)
                    .scopeType(CalendarSyncScopeType.TEAM).scopeId(SCOPE_ID)
                    .taskType(ScheduledTaskType.SURVEY).scheduledAt(FUTURE)
                    .status(ScheduledTaskStatus.PENDING).payloadJson("{}").build();
            given(scheduledTaskRepository.findByScheduleIdAndDeletedAtIsNull(SCHEDULE_ID))
                    .willReturn(List.of(task));

            // when
            List<ScheduleScheduledTaskEntity> result = service.findTasksForSchedule(SCHEDULE_ID);

            // then
            assertThat(result).hasSize(1).containsExactly(task);
        }
    }
}
