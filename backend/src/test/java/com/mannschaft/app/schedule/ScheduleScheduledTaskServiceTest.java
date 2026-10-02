package com.mannschaft.app.schedule;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.mannschaft.app.common.BusinessException;
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
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
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
    /** JST+09:00 の翌日（UTC+9）。DTO（OffsetDateTime）として使用する。 */
    private static final OffsetDateTime FUTURE = OffsetDateTime.now(ZoneOffset.ofHours(9)).plusDays(1);
    /** FUTURE を Asia/Tokyo に変換した LocalDateTime。Entity builder に渡す際に使用する。 */
    private static final LocalDateTime FUTURE_JST = FUTURE.atZoneSameInstant(ZoneId.of("Asia/Tokyo")).toLocalDateTime();

    @org.junit.jupiter.api.BeforeEach
    void setUp() {
        service = new ScheduleScheduledTaskService(scheduledTaskRepository, objectMapper);
    }

    private CreateSurveyRequest createSurveyRequest() {
        return new CreateSurveyRequest(
                "出欠アンケート", "説明",
                false, false,
                com.mannschaft.app.survey.ResultsVisibility.AFTER_CLOSE,
                com.mannschaft.app.survey.DistributionMode.ALL,
                null, null, null, null, null, null,
                null, null, null, null, null);
    }

    @Nested
    @DisplayName("registerTasks")
    class RegisterTasks {

        @Test
        @DisplayName("予約アンケート_PENDINGで保存されpayloadがJSON直列化される")
        void 予約アンケート_PENDINGで保存されpayloadがJSON直列化される() {
            // given
            ScheduledSurveyRequest surveyReq = new ScheduledSurveyRequest(FUTURE, createSurveyRequest());
            LocalDateTime expectedJst = FUTURE.atZoneSameInstant(ZoneId.of("Asia/Tokyo")).toLocalDateTime();

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
            // OffsetDateTime → JST LocalDateTime に変換されて保存される
            assertThat(saved.getScheduledAt()).isEqualTo(expectedJst);
            assertThat(saved.getCreatedBy()).isEqualTo(CREATED_BY);
            // payload にアンケートタイトルが JSON として含まれる
            assertThat(saved.getPayloadJson()).contains("出欠アンケート");
        }

        @Test
        @DisplayName("予約出欠募集_PENDINGで保存され出欠設定がpayloadに入る")
        void 予約出欠募集_PENDINGで保存され出欠設定がpayloadに入る() {
            // given
            ScheduledAttendanceRequest attendanceReq = new ScheduledAttendanceRequest(
                    FUTURE, OffsetDateTime.now(ZoneOffset.ofHours(9)).plusDays(3),
                    "REQUIRED", "MEMBER_PLUS");
            LocalDateTime expectedJst = FUTURE.atZoneSameInstant(ZoneId.of("Asia/Tokyo")).toLocalDateTime();

            // when
            service.registerTasks(SCHEDULE_ID, CalendarSyncScopeType.ORGANIZATION, SCOPE_ID, ORG_ID, CREATED_BY,
                    null, attendanceReq);

            // then
            verify(scheduledTaskRepository).save(taskCaptor.capture());
            ScheduleScheduledTaskEntity saved = taskCaptor.getValue();
            assertThat(saved.getTaskType()).isEqualTo(ScheduledTaskType.ATTENDANCE);
            assertThat(saved.getStatus()).isEqualTo(ScheduledTaskStatus.PENDING);
            // OffsetDateTime → JST LocalDateTime に変換されて保存される
            assertThat(saved.getScheduledAt()).isEqualTo(expectedJst);
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
                    .taskType(ScheduledTaskType.SURVEY).scheduledAt(FUTURE_JST)
                    .status(ScheduledTaskStatus.PENDING).payloadJson("{}").build();
            ScheduleScheduledTaskEntity alreadyCreated = ScheduleScheduledTaskEntity.builder()
                    .scheduleId(SCHEDULE_ID).organizationId(ORG_ID)
                    .scopeType(CalendarSyncScopeType.TEAM).scopeId(SCOPE_ID)
                    .taskType(ScheduledTaskType.ATTENDANCE).scheduledAt(FUTURE_JST)
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
                    .taskType(ScheduledTaskType.SURVEY).scheduledAt(FUTURE_JST)
                    .status(ScheduledTaskStatus.PENDING).payloadJson("{}").build();
            given(scheduledTaskRepository.findByScheduleIdAndDeletedAtIsNull(SCHEDULE_ID))
                    .willReturn(List.of(task));

            // when
            List<ScheduleScheduledTaskEntity> result = service.findTasksForSchedule(SCHEDULE_ID);

            // then
            assertThat(result).hasSize(1).containsExactly(task);
        }
    }

    @Nested
    @DisplayName("findTaskResponsesForSchedule")
    class FindTaskResponsesForSchedule {

        @Test
        @DisplayName("予約タスクがレスポンスDTOに変換される（PENDING含む全状態）")
        void 予約タスクがレスポンスDTOに変換される() {
            // given
            ScheduleScheduledTaskEntity pending = ScheduleScheduledTaskEntity.builder()
                    .scheduleId(SCHEDULE_ID).organizationId(ORG_ID)
                    .scopeType(CalendarSyncScopeType.TEAM).scopeId(SCOPE_ID)
                    .taskType(ScheduledTaskType.SURVEY).scheduledAt(FUTURE_JST)
                    .status(ScheduledTaskStatus.PENDING).payloadJson("{}").build();
            ScheduleScheduledTaskEntity created = ScheduleScheduledTaskEntity.builder()
                    .scheduleId(SCHEDULE_ID).organizationId(ORG_ID)
                    .scopeType(CalendarSyncScopeType.TEAM).scopeId(SCOPE_ID)
                    .taskType(ScheduledTaskType.ATTENDANCE).scheduledAt(FUTURE_JST)
                    .status(ScheduledTaskStatus.CREATED).materializedEntityId(777L)
                    .payloadJson("{}").build();
            given(scheduledTaskRepository.findByScheduleIdAndDeletedAtIsNull(SCHEDULE_ID))
                    .willReturn(List.of(pending, created));

            // when
            var result = service.findTaskResponsesForSchedule(SCHEDULE_ID);

            // then
            assertThat(result).hasSize(2);
            assertThat(result).anySatisfy(r -> {
                assertThat(r.getTaskType()).isEqualTo("SURVEY");
                assertThat(r.getStatus()).isEqualTo("PENDING");
                assertThat(r.getMaterializedEntityId()).isNull();
            });
            assertThat(result).anySatisfy(r -> {
                assertThat(r.getTaskType()).isEqualTo("ATTENDANCE");
                assertThat(r.getStatus()).isEqualTo("CREATED");
                assertThat(r.getMaterializedEntityId()).isEqualTo(777L);
            });
        }
    }

    @Nested
    @DisplayName("cancelTask")
    class CancelTask {

        private final java.util.UUID TASK_ID = java.util.UUID.randomUUID();

        @Test
        @DisplayName("PENDINGかつスコープ一致_CANCELLEDになりsaveされる")
        void PENDINGかつスコープ一致_取消成功() {
            // given
            ScheduleScheduledTaskEntity task = ScheduleScheduledTaskEntity.builder()
                    .scheduleId(SCHEDULE_ID).organizationId(ORG_ID)
                    .scopeType(CalendarSyncScopeType.TEAM).scopeId(SCOPE_ID)
                    .taskType(ScheduledTaskType.SURVEY).scheduledAt(FUTURE_JST)
                    .status(ScheduledTaskStatus.PENDING).payloadJson("{}").build();
            given(scheduledTaskRepository.findById(TASK_ID)).willReturn(java.util.Optional.of(task));

            // when
            service.cancelTask(TASK_ID, CalendarSyncScopeType.TEAM, SCOPE_ID);

            // then
            assertThat(task.getStatus()).isEqualTo(ScheduledTaskStatus.CANCELLED);
            verify(scheduledTaskRepository).save(task);
        }

        @Test
        @DisplayName("不存在_SCHEDULED_TASK_NOT_FOUND例外")
        void 不存在_例外() {
            given(scheduledTaskRepository.findById(TASK_ID)).willReturn(java.util.Optional.empty());

            assertThatThrownBy(() -> service.cancelTask(TASK_ID, CalendarSyncScopeType.TEAM, SCOPE_ID))
                    .isInstanceOf(BusinessException.class)
                    .extracting(e -> ((BusinessException) e).getErrorCode())
                    .isEqualTo(ScheduleErrorCode.SCHEDULED_TASK_NOT_FOUND);
        }

        @Test
        @DisplayName("スコープ不一致_IDOR対策でNOT_FOUND例外")
        void スコープ不一致_NOT_FOUND() {
            ScheduleScheduledTaskEntity task = ScheduleScheduledTaskEntity.builder()
                    .scheduleId(SCHEDULE_ID).organizationId(ORG_ID)
                    .scopeType(CalendarSyncScopeType.TEAM).scopeId(SCOPE_ID)
                    .taskType(ScheduledTaskType.SURVEY).scheduledAt(FUTURE_JST)
                    .status(ScheduledTaskStatus.PENDING).payloadJson("{}").build();
            given(scheduledTaskRepository.findById(TASK_ID)).willReturn(java.util.Optional.of(task));

            // 別スコープID（999L）で取消要求 → 404 隠蔽
            assertThatThrownBy(() -> service.cancelTask(TASK_ID, CalendarSyncScopeType.TEAM, 999L))
                    .isInstanceOf(BusinessException.class)
                    .extracting(e -> ((BusinessException) e).getErrorCode())
                    .isEqualTo(ScheduleErrorCode.SCHEDULED_TASK_NOT_FOUND);
            verify(scheduledTaskRepository, never()).save(any());
        }

        @Test
        @DisplayName("PENDING以外_NOT_CANCELLABLE例外（409）")
        void PENDING以外_取消不能() {
            ScheduleScheduledTaskEntity task = ScheduleScheduledTaskEntity.builder()
                    .scheduleId(SCHEDULE_ID).organizationId(ORG_ID)
                    .scopeType(CalendarSyncScopeType.ORGANIZATION).scopeId(SCOPE_ID)
                    .taskType(ScheduledTaskType.SURVEY).scheduledAt(FUTURE_JST)
                    .status(ScheduledTaskStatus.CREATED).payloadJson("{}").build();
            given(scheduledTaskRepository.findById(TASK_ID)).willReturn(java.util.Optional.of(task));

            assertThatThrownBy(() -> service.cancelTask(TASK_ID, CalendarSyncScopeType.ORGANIZATION, SCOPE_ID))
                    .isInstanceOf(BusinessException.class)
                    .extracting(e -> ((BusinessException) e).getErrorCode())
                    .isEqualTo(ScheduleErrorCode.SCHEDULED_TASK_NOT_CANCELLABLE);
            verify(scheduledTaskRepository, never()).save(any());
        }
    }

    /**
     * TZ 変換の正確性テスト。
     *
     * <p>非 JST ユーザーが異なるタイムゾーンで scheduledAt を指定しても、
     * Entity には常に Asia/Tokyo の LocalDateTime として保存されることを検証する。</p>
     */
    @Nested
    @DisplayName("scheduledAt タイムゾーン変換")
    class ScheduledAtTimezoneConversion {

        private static final ZoneId JST = ZoneId.of("Asia/Tokyo");

        @Test
        @DisplayName("JST(+09:00)指定_そのままJSTLocalDateTimeで保存される")
        void JST指定_JSTLocalDateTimeで保存() {
            // given: 2026-07-01T10:00:00+09:00（JST ユーザーが 10:00 を指定）
            OffsetDateTime jstInput = OffsetDateTime.of(2026, 7, 1, 10, 0, 0, 0, ZoneOffset.ofHours(9));
            ScheduledSurveyRequest req = new ScheduledSurveyRequest(jstInput, createSurveyRequest());

            // when
            service.registerTasks(SCHEDULE_ID, CalendarSyncScopeType.TEAM, SCOPE_ID, ORG_ID, CREATED_BY,
                    List.of(req), null);

            // then: 変換後も 2026-07-01T10:00:00（JST = UTC+9 なのでそのまま）
            verify(scheduledTaskRepository).save(taskCaptor.capture());
            assertThat(taskCaptor.getValue().getScheduledAt())
                    .isEqualTo(LocalDateTime.of(2026, 7, 1, 10, 0, 0));
        }

        @Test
        @DisplayName("UTC(+00:00)指定_JST+9時間に変換されて保存される")
        void UTC指定_JST変換されて保存() {
            // given: 2026-07-01T01:00:00Z（UTC ユーザーが 01:00 UTC = JST 10:00 を指定）
            OffsetDateTime utcInput = OffsetDateTime.of(2026, 7, 1, 1, 0, 0, 0, ZoneOffset.UTC);
            ScheduledSurveyRequest req = new ScheduledSurveyRequest(utcInput, createSurveyRequest());

            // when
            service.registerTasks(SCHEDULE_ID, CalendarSyncScopeType.TEAM, SCOPE_ID, ORG_ID, CREATED_BY,
                    List.of(req), null);

            // then: 2026-07-01T01:00Z → Asia/Tokyo → 2026-07-01T10:00:00
            verify(scheduledTaskRepository).save(taskCaptor.capture());
            assertThat(taskCaptor.getValue().getScheduledAt())
                    .isEqualTo(LocalDateTime.of(2026, 7, 1, 10, 0, 0));
        }

        @Test
        @DisplayName("EST(-05:00)指定_JST+14時間に変換されて保存される")
        void EST指定_JST変換されて保存() {
            // given: 2026-06-30T21:00:00-05:00（EST ユーザーが 21:00 EST = 翌日JST 11:00 を指定）
            OffsetDateTime estInput = OffsetDateTime.of(2026, 6, 30, 21, 0, 0, 0, ZoneOffset.ofHours(-5));
            ScheduledAttendanceRequest req = new ScheduledAttendanceRequest(
                    estInput, null, null, null);

            // when
            service.registerTasks(SCHEDULE_ID, CalendarSyncScopeType.TEAM, SCOPE_ID, ORG_ID, CREATED_BY,
                    null, req);

            // then: 2026-06-30T21:00-05:00 → UTC 2026-07-01T02:00Z → Asia/Tokyo 2026-07-01T11:00:00
            verify(scheduledTaskRepository).save(taskCaptor.capture());
            assertThat(taskCaptor.getValue().getScheduledAt())
                    .isEqualTo(LocalDateTime.of(2026, 7, 1, 11, 0, 0));
        }

        @Test
        @DisplayName("予約出欠募集_UTC指定_JSTに変換されて保存される")
        void 予約出欠募集_UTC指定_JST変換されて保存() {
            // given: UTC 00:00 = JST 09:00
            OffsetDateTime utcInput = OffsetDateTime.of(2026, 8, 1, 0, 0, 0, 0, ZoneOffset.UTC);
            ScheduledAttendanceRequest req = new ScheduledAttendanceRequest(
                    utcInput, OffsetDateTime.of(2026, 8, 2, 0, 0, 0, 0, ZoneOffset.ofHours(9)),
                    "OPTIONAL", "MEMBER");

            // when
            service.registerTasks(SCHEDULE_ID, CalendarSyncScopeType.ORGANIZATION, SCOPE_ID, ORG_ID, CREATED_BY,
                    null, req);

            // then: 2026-08-01T00:00Z → Asia/Tokyo → 2026-08-01T09:00:00
            verify(scheduledTaskRepository).save(taskCaptor.capture());
            ScheduleScheduledTaskEntity saved = taskCaptor.getValue();
            assertThat(saved.getScheduledAt()).isEqualTo(LocalDateTime.of(2026, 8, 1, 9, 0, 0));
            assertThat(saved.getTaskType()).isEqualTo(ScheduledTaskType.ATTENDANCE);
            assertThat(saved.getPayloadJson()).contains("OPTIONAL");
        }
    }
}
