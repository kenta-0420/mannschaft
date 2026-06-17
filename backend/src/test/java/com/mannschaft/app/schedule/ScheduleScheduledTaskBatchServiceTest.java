package com.mannschaft.app.schedule;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.mannschaft.app.schedule.entity.ScheduleScheduledTaskEntity;
import com.mannschaft.app.schedule.repository.ScheduleScheduledTaskRepository;
import com.mannschaft.app.schedule.service.ScheduleAttendanceService;
import com.mannschaft.app.schedule.service.ScheduleScheduledTaskBatchService;
import com.mannschaft.app.survey.dto.CreateSurveyRequest;
import com.mannschaft.app.survey.dto.SurveyDetailResponse;
import com.mannschaft.app.survey.dto.SurveyResponse;
import com.mannschaft.app.survey.service.SurveyService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * {@link ScheduleScheduledTaskBatchService} の単体テスト（機能55 第二陣）。
 * PENDING → materialize → CREATED、失敗時の attempt 加算、SURVEY/ATTENDANCE 分岐を検証する。
 * survey / attendance はモックする。
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("ScheduleScheduledTaskBatchService 単体テスト")
class ScheduleScheduledTaskBatchServiceTest {

    @Mock
    private ScheduleScheduledTaskRepository scheduledTaskRepository;

    @Mock
    private SurveyService surveyService;

    @Mock
    private ScheduleAttendanceService scheduleAttendanceService;

    // 本番（Spring Boot 管理）と同等に ParameterNamesModule + JSR310 を登録する。
    // CreateSurveyRequest は @RequiredArgsConstructor のため -parameters + ParameterNamesModule で復元できる。
    private final ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();

    private ScheduleScheduledTaskBatchService batchService;

    private static final Long SCHEDULE_ID = 1L;
    private static final Long SCOPE_ID = 10L;
    private static final Long ORG_ID = 100L;
    private static final Long CREATED_BY = 999L;

    @BeforeEach
    void setUp() {
        // self（プロキシ）は runBatch の per-item ループ内でのみ使用される。
        // 本テストは materializeOne / recordFailure を直接呼ぶか、runBatch の due 空パスのみ検証するため
        // self には実体（自分自身）を渡しても問題ないが、二段構築を避けるためここでは null を渡す
        // （due 空パスでは self に触れない）。
        batchService = new ScheduleScheduledTaskBatchService(
                scheduledTaskRepository, objectMapper, surveyService, scheduleAttendanceService, null);
    }

    private String surveyPayloadJson() throws Exception {
        CreateSurveyRequest req = new CreateSurveyRequest(
                "出欠アンケート", null, false, false,
                "AFTER_CLOSE", "ALL",
                null, null, null, null, null, null, null, null, null, null);
        return objectMapper.writeValueAsString(req);
    }

    private ScheduleScheduledTaskEntity pendingSurveyTask(String payload) {
        return ScheduleScheduledTaskEntity.builder()
                .scheduleId(SCHEDULE_ID).organizationId(ORG_ID)
                .scopeType(CalendarSyncScopeType.TEAM).scopeId(SCOPE_ID)
                .taskType(ScheduledTaskType.SURVEY)
                .scheduledAt(LocalDateTime.now().minusMinutes(1))
                .status(ScheduledTaskStatus.PENDING)
                .payloadJson(payload)
                .createdBy(CREATED_BY)
                .build();
    }

    private ScheduleScheduledTaskEntity pendingAttendanceTask() {
        return ScheduleScheduledTaskEntity.builder()
                .scheduleId(SCHEDULE_ID).organizationId(ORG_ID)
                .scopeType(CalendarSyncScopeType.ORGANIZATION).scopeId(SCOPE_ID)
                .taskType(ScheduledTaskType.ATTENDANCE)
                .scheduledAt(LocalDateTime.now().minusMinutes(1))
                .status(ScheduledTaskStatus.PENDING)
                .payloadJson("{}")
                .createdBy(CREATED_BY)
                .build();
    }

    @Nested
    @DisplayName("materializeOne")
    class MaterializeOne {

        @Test
        @DisplayName("SURVEY_createSurveyとpublishSurveyが呼ばれCREATEDになる")
        void SURVEY_createSurveyとpublishSurveyが呼ばれCREATEDになる() throws Exception {
            // given
            ScheduleScheduledTaskEntity task = pendingSurveyTask(surveyPayloadJson());
            given(scheduledTaskRepository.findById(task.getId())).willReturn(Optional.of(task));

            SurveyResponse surveyResponse = SurveyResponse.builder().id(555L).build();
            SurveyDetailResponse detail = new SurveyDetailResponse(surveyResponse, List.of());
            given(surveyService.createSurvey(eq("TEAM"), eq(SCOPE_ID), eq(CREATED_BY), any()))
                    .willReturn(detail);

            // when
            batchService.materializeOne(task);

            // then
            verify(surveyService).createSurvey(eq("TEAM"), eq(SCOPE_ID), eq(CREATED_BY), any());
            verify(surveyService).publishSurvey("TEAM", SCOPE_ID, 555L);
            assertThat(task.getStatus()).isEqualTo(ScheduledTaskStatus.CREATED);
            assertThat(task.getMaterializedEntityId()).isEqualTo(555L);
        }

        @Test
        @DisplayName("ATTENDANCE_openAttendanceSolicitationが呼ばれCREATEDになる")
        void ATTENDANCE_openAttendanceSolicitationが呼ばれCREATEDになる() throws Exception {
            // given
            ScheduleScheduledTaskEntity task = pendingAttendanceTask();
            given(scheduledTaskRepository.findById(task.getId())).willReturn(Optional.of(task));

            // when
            batchService.materializeOne(task);

            // then
            verify(scheduleAttendanceService).openAttendanceSolicitation(SCHEDULE_ID);
            assertThat(task.getStatus()).isEqualTo(ScheduledTaskStatus.CREATED);
            assertThat(task.getMaterializedEntityId()).isEqualTo(SCHEDULE_ID);
        }

        @Test
        @DisplayName("既にPENDINGでない_何もしない")
        void 既にPENDINGでない_何もしない() throws Exception {
            // given
            ScheduleScheduledTaskEntity task = pendingAttendanceTask();
            task.cancel(); // CANCELLED
            given(scheduledTaskRepository.findById(task.getId())).willReturn(Optional.of(task));

            // when
            batchService.materializeOne(task);

            // then
            verify(scheduleAttendanceService, never()).openAttendanceSolicitation(anyLong());
        }
    }

    @Nested
    @DisplayName("recordFailure")
    class RecordFailure {

        @Test
        @DisplayName("上限未満_attempt加算しPENDINGのまま据え置き")
        void 上限未満_attempt加算しPENDINGのまま据え置き() {
            // given
            ScheduleScheduledTaskEntity task = pendingAttendanceTask(); // attemptCount=0
            UUID id = task.getId();
            given(scheduledTaskRepository.findById(id)).willReturn(Optional.of(task));

            // when
            batchService.recordFailure(id, "一時的なエラー");

            // then
            assertThat(task.getAttemptCount()).isEqualTo(1);
            assertThat(task.getStatus()).isEqualTo(ScheduledTaskStatus.PENDING);
            assertThat(task.getLastError()).isEqualTo("一時的なエラー");
            verify(scheduledTaskRepository).save(task);
        }

        @Test
        @DisplayName("上限到達_FAILED確定")
        void 上限到達_FAILED確定() {
            // given: attemptCount を上限直前にしておく
            ScheduleScheduledTaskEntity task = pendingAttendanceTask().toBuilder()
                    .attemptCount(ScheduleScheduledTaskBatchService.MAX_ATTEMPTS - 1)
                    .build();
            UUID id = task.getId();
            given(scheduledTaskRepository.findById(id)).willReturn(Optional.of(task));

            // when
            batchService.recordFailure(id, "恒久的なエラー");

            // then
            assertThat(task.getAttemptCount()).isEqualTo(ScheduleScheduledTaskBatchService.MAX_ATTEMPTS);
            assertThat(task.getStatus()).isEqualTo(ScheduledTaskStatus.FAILED);
        }
    }

    @Nested
    @DisplayName("runBatch")
    class RunBatch {

        @Test
        @DisplayName("due無し_何も処理しない")
        void due無し_何も処理しない() {
            // given
            given(scheduledTaskRepository.findByStatusAndScheduledAtBeforeAndDeletedAtIsNull(
                    eq(ScheduledTaskStatus.PENDING), any())).willReturn(List.of());

            // when
            batchService.runBatch();

            // then
            verify(surveyService, never()).createSurvey(any(), anyLong(), anyLong(), any());
            verify(scheduleAttendanceService, never()).openAttendanceSolicitation(anyLong());
        }
    }
}
