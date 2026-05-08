package com.mannschaft.app.errorreport.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.mannschaft.app.auth.repository.UserRepository;
import com.mannschaft.app.common.AccessControlService;
import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.errorreport.ErrorReportActivityType;
import com.mannschaft.app.errorreport.ErrorReportErrorCode;
import com.mannschaft.app.errorreport.ErrorReportSeverity;
import com.mannschaft.app.errorreport.ErrorReportStatus;
import com.mannschaft.app.errorreport.ErrorReportWorkflowStage;
import com.mannschaft.app.errorreport.dto.KanbanResponse;
import com.mannschaft.app.errorreport.entity.ErrorReportEntity;
import com.mannschaft.app.errorreport.repository.ErrorReportActivityRepository;
import com.mannschaft.app.errorreport.repository.ErrorReportOccurrenceRepository;
import com.mannschaft.app.errorreport.repository.ErrorReportRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.time.LocalDateTime;
import java.util.Collections;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * F12.5 Phase 2 — {@link ErrorReportService} のワークフロー / 担当者 / コメント機能の単体テスト。
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("ErrorReportService Phase2 単体テスト")
class ErrorReportServiceTest {

    @Mock
    private ErrorReportRepository errorReportRepository;
    @Mock
    private ErrorReportNotifier errorReportNotifier;
    @Mock
    private StringRedisTemplate redisTemplate;
    @Mock
    private ErrorReportActivityRepository activityRepository;
    @Mock
    private ErrorReportOccurrenceRepository occurrenceRepository;
    @Mock
    private ErrorReportActivityService activityService;
    @Mock
    private AccessControlService accessControlService;
    @Mock
    private UserRepository userRepository;
    @Mock
    private ObjectMapper objectMapper;
    @Mock
    private ErrorReportAiAnalysisService aiAnalysisService;
    @Mock
    private com.mannschaft.app.errorreport.repository.ErrorReportAiAnalysisRepository aiAnalysisRepository;

    @InjectMocks
    private ErrorReportService service;

    private static final Long REPORT_ID = 100L;
    private static final Long ACTOR_ID = 5L;
    private static final Long ASSIGNEE_ID = 7L;

    private ErrorReportEntity createReport(ErrorReportStatus status, ErrorReportWorkflowStage stage) {
        return ErrorReportEntity.builder()
                .errorMessage("test error")
                .pageUrl("/foo")
                .occurredAt(LocalDateTime.now())
                .status(status)
                .severity(ErrorReportSeverity.MEDIUM)
                .errorHash("hash")
                .occurrenceCount(1)
                .affectedUserCount(1)
                .firstOccurredAt(LocalDateTime.now())
                .lastOccurredAt(LocalDateTime.now())
                .workflowStage(stage)
                .build();
    }

    // ========================================
    // updateWorkflowStage
    // ========================================

    @Nested
    @DisplayName("updateWorkflowStage (P2-E: status 自動遷移)")
    class UpdateWorkflowStage {

        @Test
        @DisplayName("status=INVESTIGATING かつ stage=INVESTIGATION_STARTED は status 維持で更新")
        void allowsValidTransition() {
            ErrorReportEntity report = createReport(ErrorReportStatus.INVESTIGATING, null);
            given(errorReportRepository.findById(REPORT_ID)).willReturn(Optional.of(report));

            ErrorReportEntity result = service.updateWorkflowStage(
                    REPORT_ID, ErrorReportWorkflowStage.INVESTIGATION_STARTED, ACTOR_ID);

            assertThat(result.getWorkflowStage()).isEqualTo(ErrorReportWorkflowStage.INVESTIGATION_STARTED);
            assertThat(result.getStatus()).isEqualTo(ErrorReportStatus.INVESTIGATING);
            verify(activityService).record(eq(REPORT_ID), eq(ACTOR_ID),
                    eq(ErrorReportActivityType.WORKFLOW_CHANGED), isNull(), anyMap());
        }

        @Test
        @DisplayName("status=NEW → INVESTIGATION_STARTED で status を INVESTIGATING に自動昇格")
        void promotesNewToInvestigatingOnInvestigationStarted() {
            ErrorReportEntity report = createReport(ErrorReportStatus.NEW, null);
            given(errorReportRepository.findById(REPORT_ID)).willReturn(Optional.of(report));

            ErrorReportEntity result = service.updateWorkflowStage(
                    REPORT_ID, ErrorReportWorkflowStage.INVESTIGATION_STARTED, ACTOR_ID);

            assertThat(result.getStatus()).isEqualTo(ErrorReportStatus.INVESTIGATING);
            assertThat(result.getWorkflowStage()).isEqualTo(ErrorReportWorkflowStage.INVESTIGATION_STARTED);
            // STATUS_CHANGED と WORKFLOW_CHANGED の両方が記録される
            verify(activityService).record(eq(REPORT_ID), eq(ACTOR_ID),
                    eq(ErrorReportActivityType.STATUS_CHANGED), isNull(), anyMap());
            verify(activityService).record(eq(REPORT_ID), eq(ACTOR_ID),
                    eq(ErrorReportActivityType.WORKFLOW_CHANGED), isNull(), anyMap());
        }

        @Test
        @DisplayName("status=INVESTIGATING → TEST_COMPLETED で status を RESOLVED に自動昇格")
        void promotesInvestigatingToResolvedOnTestCompleted() {
            ErrorReportEntity report = createReport(ErrorReportStatus.INVESTIGATING,
                    ErrorReportWorkflowStage.FIX_IN_PROGRESS);
            given(errorReportRepository.findById(REPORT_ID)).willReturn(Optional.of(report));

            ErrorReportEntity result = service.updateWorkflowStage(
                    REPORT_ID, ErrorReportWorkflowStage.TEST_COMPLETED, ACTOR_ID);

            assertThat(result.getStatus()).isEqualTo(ErrorReportStatus.RESOLVED);
            assertThat(result.getWorkflowStage()).isEqualTo(ErrorReportWorkflowStage.TEST_COMPLETED);
            assertThat(result.getResolvedBy()).isEqualTo(ACTOR_ID);
        }

        @Test
        @DisplayName("status=RESOLVED → FIX_IN_PROGRESS で status を REOPENED に復帰")
        void demotesResolvedToReopenedOnFixInProgress() {
            ErrorReportEntity report = createReport(ErrorReportStatus.RESOLVED,
                    ErrorReportWorkflowStage.RELEASED);
            given(errorReportRepository.findById(REPORT_ID)).willReturn(Optional.of(report));

            ErrorReportEntity result = service.updateWorkflowStage(
                    REPORT_ID, ErrorReportWorkflowStage.FIX_IN_PROGRESS, ACTOR_ID);

            assertThat(result.getStatus()).isEqualTo(ErrorReportStatus.REOPENED);
            assertThat(result.getWorkflowStage()).isEqualTo(ErrorReportWorkflowStage.FIX_IN_PROGRESS);
        }

        @Test
        @DisplayName("status=RESOLVED かつ stage=RELEASED は status 維持で更新")
        void allowsReleasedOnResolved() {
            ErrorReportEntity report = createReport(ErrorReportStatus.RESOLVED, null);
            given(errorReportRepository.findById(REPORT_ID)).willReturn(Optional.of(report));

            ErrorReportEntity result = service.updateWorkflowStage(
                    REPORT_ID, ErrorReportWorkflowStage.RELEASED, ACTOR_ID);

            assertThat(result.getWorkflowStage()).isEqualTo(ErrorReportWorkflowStage.RELEASED);
            assertThat(result.getStatus()).isEqualTo(ErrorReportStatus.RESOLVED);
        }

        @Test
        @DisplayName("status=REOPENED → INVESTIGATION_STARTED は status 維持で更新")
        void allowsStageOnReopened() {
            ErrorReportEntity report = createReport(ErrorReportStatus.REOPENED, null);
            given(errorReportRepository.findById(REPORT_ID)).willReturn(Optional.of(report));

            ErrorReportEntity result = service.updateWorkflowStage(
                    REPORT_ID, ErrorReportWorkflowStage.INVESTIGATION_STARTED, ACTOR_ID);

            assertThat(result.getStatus()).isEqualTo(ErrorReportStatus.REOPENED);
            assertThat(result.getWorkflowStage()).isEqualTo(ErrorReportWorkflowStage.INVESTIGATION_STARTED);
        }

        @Test
        @DisplayName("status=IGNORED への操作は ERROR_REPORT_005 で拒否")
        void rejectsOperationOnIgnored() {
            ErrorReportEntity report = createReport(ErrorReportStatus.IGNORED, null);
            given(errorReportRepository.findById(REPORT_ID)).willReturn(Optional.of(report));

            assertThatThrownBy(() -> service.updateWorkflowStage(
                    REPORT_ID, ErrorReportWorkflowStage.INVESTIGATION_STARTED, ACTOR_ID))
                    .isInstanceOf(BusinessException.class)
                    .extracting("errorCode")
                    .isEqualTo(ErrorReportErrorCode.ERROR_REPORT_005);
        }

        @Test
        @DisplayName("newStage=NULL（未着手）に戻すと status=NEW にリセット")
        void resetsToNewWhenStageIsNull() {
            ErrorReportEntity report = createReport(ErrorReportStatus.INVESTIGATING,
                    ErrorReportWorkflowStage.INVESTIGATION_STARTED);
            given(errorReportRepository.findById(REPORT_ID)).willReturn(Optional.of(report));

            ErrorReportEntity result = service.updateWorkflowStage(REPORT_ID, null, ACTOR_ID);

            assertThat(result.getStatus()).isEqualTo(ErrorReportStatus.NEW);
            assertThat(result.getWorkflowStage()).isNull();
        }

        @Test
        @DisplayName("RESOLVED から newStage=NULL に戻すと status=REOPENED に復帰")
        void demotesResolvedToReopenedWhenStageIsNull() {
            ErrorReportEntity report = createReport(ErrorReportStatus.RESOLVED,
                    ErrorReportWorkflowStage.RELEASED);
            given(errorReportRepository.findById(REPORT_ID)).willReturn(Optional.of(report));

            ErrorReportEntity result = service.updateWorkflowStage(REPORT_ID, null, ACTOR_ID);

            assertThat(result.getStatus()).isEqualTo(ErrorReportStatus.REOPENED);
            assertThat(result.getWorkflowStage()).isNull();
        }
    }

    // ========================================
    // assign
    // ========================================

    @Nested
    @DisplayName("assign")
    class Assign {

        @Test
        @DisplayName("SYSTEM_ADMIN への割り当ては成功し通知される")
        void assignsToSystemAdmin() {
            ErrorReportEntity report = createReport(ErrorReportStatus.NEW, null);
            given(errorReportRepository.findById(REPORT_ID)).willReturn(Optional.of(report));
            given(accessControlService.isSystemAdmin(ASSIGNEE_ID)).willReturn(true);

            ErrorReportEntity result = service.assign(REPORT_ID, ASSIGNEE_ID, ACTOR_ID);

            assertThat(result.getAssigneeId()).isEqualTo(ASSIGNEE_ID);
            verify(activityService).record(eq(REPORT_ID), eq(ACTOR_ID),
                    eq(ErrorReportActivityType.ASSIGNEE_CHANGED), isNull(), anyMap());
            verify(errorReportNotifier).notifyAssignment(report, ASSIGNEE_ID);
        }

        @Test
        @DisplayName("非SYSTEM_ADMIN への割り当ては ERROR_REPORT_006 で失敗")
        void rejectsNonSystemAdmin() {
            ErrorReportEntity report = createReport(ErrorReportStatus.NEW, null);
            given(errorReportRepository.findById(REPORT_ID)).willReturn(Optional.of(report));
            given(accessControlService.isSystemAdmin(ASSIGNEE_ID)).willReturn(false);

            assertThatThrownBy(() -> service.assign(REPORT_ID, ASSIGNEE_ID, ACTOR_ID))
                    .isInstanceOf(BusinessException.class)
                    .extracting("errorCode")
                    .isEqualTo(ErrorReportErrorCode.ERROR_REPORT_006);
        }

        @Test
        @DisplayName("assigneeId=null は担当者解除として成功し通知は送られない")
        void unassignDoesNotNotify() {
            ErrorReportEntity report = createReport(ErrorReportStatus.NEW, null);
            report.setAssigneeId(99L);
            given(errorReportRepository.findById(REPORT_ID)).willReturn(Optional.of(report));

            ErrorReportEntity result = service.assign(REPORT_ID, null, ACTOR_ID);

            assertThat(result.getAssigneeId()).isNull();
            verify(errorReportNotifier, never()).notifyAssignment(any(), anyLong());
        }
    }

    // ========================================
    // addComment
    // ========================================

    @Nested
    @DisplayName("addComment")
    class AddComment {

        @Test
        @DisplayName("コメントが activityService に COMMENT_ADDED で記録される")
        void recordsComment() {
            ErrorReportEntity report = createReport(ErrorReportStatus.NEW, null);
            given(errorReportRepository.findById(REPORT_ID)).willReturn(Optional.of(report));

            service.addComment(REPORT_ID, "テストコメント", ACTOR_ID);

            verify(activityService).record(eq(REPORT_ID), eq(ACTOR_ID),
                    eq(ErrorReportActivityType.COMMENT_ADDED), eq("テストコメント"), isNull());
        }

        @Test
        @DisplayName("存在しないIDは ERROR_REPORT_NOT_FOUND で失敗")
        void rejectsNotFound() {
            given(errorReportRepository.findById(REPORT_ID)).willReturn(Optional.empty());

            assertThatThrownBy(() -> service.addComment(REPORT_ID, "本文", ACTOR_ID))
                    .isInstanceOf(BusinessException.class)
                    .extracting("errorCode")
                    .isEqualTo(ErrorReportErrorCode.ERROR_REPORT_NOT_FOUND);
        }
    }

    // ========================================
    // F12.5 Phase 2-E — Kanban
    // ========================================

    @Nested
    @DisplayName("fetchKanban")
    class FetchKanban {

        @Test
        @DisplayName("6 カラム返り、NULL カラムには status IN (NEW,INVESTIGATING,REOPENED) AND stage IS NULL のカードが入る")
        void returnsSixColumns() {
            ErrorReportEntity nullStaged = createReport(ErrorReportStatus.NEW, null);
            // setId を直接呼べないため、リフレクションを介さず id 未設定のまま検証する
            Page<ErrorReportEntity> nullPage = new PageImpl<>(
                    List.of(nullStaged), PageRequest.of(0, 50), 1);
            Page<ErrorReportEntity> emptyPage = new PageImpl<>(
                    Collections.emptyList(), PageRequest.of(0, 50), 0);

            given(errorReportRepository.findByStatusInAndWorkflowStageIsNullOrderByLastOccurredAtDesc(
                    eq(List.of(ErrorReportStatus.NEW,
                            ErrorReportStatus.INVESTIGATING,
                            ErrorReportStatus.REOPENED)), any()))
                    .willReturn(nullPage);
            given(errorReportRepository.findByWorkflowStageOrderByLastOccurredAtDesc(any(), any()))
                    .willReturn(emptyPage);
            given(aiAnalysisRepository.findIdsHavingSuccessfulAnalysis(any()))
                    .willReturn(Collections.emptyList());

            KanbanResponse response = service.fetchKanban();

            assertThat(response.getColumns()).hasSize(6);
            assertThat(response.getColumns().get(0).getStageKey()).isEqualTo("NULL");
            assertThat(response.getColumns().get(0).getCards()).hasSize(1);
            assertThat(response.getColumns().get(1).getStageKey()).isEqualTo("INVESTIGATION_STARTED");
            assertThat(response.getColumns().get(5).getStageKey()).isEqualTo("RELEASED");
        }

        @Test
        @DisplayName("空状態でも 6 カラムを返し、各カラムは hasMore=false")
        void returnsAllColumnsEvenWhenEmpty() {
            Page<ErrorReportEntity> emptyPage = new PageImpl<>(
                    Collections.emptyList(), PageRequest.of(0, 50), 0);
            given(errorReportRepository.findByStatusInAndWorkflowStageIsNullOrderByLastOccurredAtDesc(any(), any()))
                    .willReturn(emptyPage);
            given(errorReportRepository.findByWorkflowStageOrderByLastOccurredAtDesc(any(), any()))
                    .willReturn(emptyPage);
            // reportIds が空なら aiAnalysisRepository は呼ばれないため stub 不要

            KanbanResponse response = service.fetchKanban();

            assertThat(response.getColumns()).hasSize(6);
            response.getColumns().forEach(c -> {
                assertThat(c.getCards()).isEmpty();
                assertThat(c.isHasMore()).isFalse();
                assertThat(c.getTotalCount()).isZero();
            });
        }
    }

    // ========================================
    // F10.6 Phase 10-β-1 — recordBackendException
    // ========================================

    @Nested
    @DisplayName("recordBackendException (F10.6 Phase 10-β-1)")
    class RecordBackendException {

        @Test
        @DisplayName("新規例外: status=NEW で error_reports に保存され、HIGH 以上は Slack 通知される")
        void newException_savedAsNewAndNotifiedWhenHigh() {
            given(errorReportRepository.findByErrorHash(org.mockito.ArgumentMatchers.anyString()))
                    .willReturn(Optional.empty());
            given(errorReportRepository.save(any(ErrorReportEntity.class)))
                    .willAnswer(inv -> inv.getArgument(0));

            RuntimeException ex = new RuntimeException("boom");
            ErrorReportEntity result = service.recordBackendException(ex, null, ErrorReportSeverity.HIGH);

            assertThat(result.getStatus()).isEqualTo(ErrorReportStatus.NEW);
            assertThat(result.getSeverity()).isEqualTo(ErrorReportSeverity.HIGH);
            assertThat(result.getErrorMessage()).contains("RuntimeException");
            assertThat(result.getErrorMessage()).contains("boom");
            assertThat(result.getErrorHash()).isNotBlank();
            assertThat(result.getErrorHash()).hasSize(64); // SHA-256 hex
            assertThat(result.getOccurrenceCount()).isEqualTo(1);
            assertThat(result.getAffectedUserCount()).isZero();
            // HIGH 以上は Slack + SYSTEM_ADMIN 通知
            verify(errorReportNotifier).notifySlack(any(ErrorReportEntity.class));
            verify(errorReportNotifier).notifySystemAdmins(any(ErrorReportEntity.class));
        }

        @Test
        @DisplayName("MEDIUM severity の新規例外は Slack/SYSTEM_ADMIN 通知が走らない")
        void newException_notNotifiedWhenMedium() {
            given(errorReportRepository.findByErrorHash(org.mockito.ArgumentMatchers.anyString()))
                    .willReturn(Optional.empty());
            given(errorReportRepository.save(any(ErrorReportEntity.class)))
                    .willAnswer(inv -> inv.getArgument(0));

            service.recordBackendException(new IllegalArgumentException("bad"),
                    null, ErrorReportSeverity.MEDIUM);

            verify(errorReportNotifier, never()).notifySlack(any());
            verify(errorReportNotifier, never()).notifySystemAdmins(any());
        }

        @Test
        @DisplayName("requestId は MDC から取得して error_reports.request_id に積まれる")
        void requestId_isReadFromMdc() {
            given(errorReportRepository.findByErrorHash(org.mockito.ArgumentMatchers.anyString()))
                    .willReturn(Optional.empty());
            given(errorReportRepository.save(any(ErrorReportEntity.class)))
                    .willAnswer(inv -> inv.getArgument(0));

            org.slf4j.MDC.put("requestId", "req-xyz");
            try {
                ErrorReportEntity saved = service.recordBackendException(
                        new RuntimeException("x"), null, ErrorReportSeverity.MEDIUM);
                assertThat(saved.getRequestId()).isEqualTo("req-xyz");
            } finally {
                org.slf4j.MDC.clear();
            }
        }

        @Test
        @DisplayName("エラーハッシュは ex クラス名 + 先頭スタックフレームから計算される（同一例外で同一ハッシュ）")
        void errorHash_isStable() {
            given(errorReportRepository.findByErrorHash(org.mockito.ArgumentMatchers.anyString()))
                    .willReturn(Optional.empty());
            given(errorReportRepository.save(any(ErrorReportEntity.class)))
                    .willAnswer(inv -> inv.getArgument(0));

            // 同じスタックトレースを持つ 2 つの例外を作る
            RuntimeException ex1 = makeException("at A");
            RuntimeException ex2 = makeException("at A");

            ErrorReportEntity r1 = service.recordBackendException(ex1, null, ErrorReportSeverity.LOW);
            ErrorReportEntity r2 = service.recordBackendException(ex2, null, ErrorReportSeverity.LOW);

            assertThat(r1.getErrorHash()).isEqualTo(r2.getErrorHash());
        }

        private RuntimeException makeException(String msg) {
            RuntimeException ex = new RuntimeException(msg);
            ex.setStackTrace(new StackTraceElement[]{
                    new StackTraceElement("com.mannschaft.app.Foo", "bar", "Foo.java", 10)
            });
            return ex;
        }

        @Test
        @DisplayName("HttpServletRequest 経由で pageUrl/userAgent/ipAddress が取得される")
        void requestFields_areExtracted() {
            given(errorReportRepository.findByErrorHash(org.mockito.ArgumentMatchers.anyString()))
                    .willReturn(Optional.empty());
            given(errorReportRepository.save(any(ErrorReportEntity.class)))
                    .willAnswer(inv -> inv.getArgument(0));

            jakarta.servlet.http.HttpServletRequest req =
                    org.mockito.Mockito.mock(jakarta.servlet.http.HttpServletRequest.class);
            given(req.getRequestURI()).willReturn("/api/v1/foo");
            given(req.getHeader("User-Agent")).willReturn("UA-1");
            given(req.getHeader("X-Forwarded-For")).willReturn("203.0.113.1, 10.0.0.1");

            ErrorReportEntity saved = service.recordBackendException(
                    new RuntimeException("e"), req, ErrorReportSeverity.MEDIUM);

            assertThat(saved.getPageUrl()).isEqualTo("/api/v1/foo");
            assertThat(saved.getUserAgent()).isEqualTo("UA-1");
            assertThat(saved.getIpAddress()).isEqualTo("203.0.113.1");
        }

        @Test
        @DisplayName("既存 RESOLVED と同一ハッシュ: REOPENED に遷移し regression 通知が走る")
        void existing_resolved_triggersRegression() {
            ErrorReportEntity existing = ErrorReportEntity.builder()
                    .errorMessage("dup")
                    .pageUrl("/p")
                    .occurredAt(LocalDateTime.now())
                    .status(ErrorReportStatus.RESOLVED)
                    .severity(ErrorReportSeverity.HIGH)
                    .errorHash("h")
                    .occurrenceCount(5)
                    .affectedUserCount(1)
                    .firstOccurredAt(LocalDateTime.now())
                    .lastOccurredAt(LocalDateTime.now())
                    .build();
            given(errorReportRepository.findByErrorHash(org.mockito.ArgumentMatchers.anyString()))
                    .willReturn(Optional.of(existing));

            ErrorReportEntity result = service.recordBackendException(
                    new RuntimeException("re"), null, ErrorReportSeverity.HIGH);

            assertThat(result.getStatus()).isEqualTo(ErrorReportStatus.REOPENED);
            verify(errorReportNotifier).notifyRegression(any(ErrorReportEntity.class));
        }
    }
}
