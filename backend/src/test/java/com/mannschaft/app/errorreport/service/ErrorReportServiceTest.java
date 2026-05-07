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
import org.springframework.data.redis.core.StringRedisTemplate;

import java.time.LocalDateTime;
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
    @DisplayName("updateWorkflowStage")
    class UpdateWorkflowStage {

        @Test
        @DisplayName("status=INVESTIGATING かつ stage=INVESTIGATION_STARTED は許可")
        void allowsValidTransition() {
            ErrorReportEntity report = createReport(ErrorReportStatus.INVESTIGATING, null);
            given(errorReportRepository.findById(REPORT_ID)).willReturn(Optional.of(report));

            ErrorReportEntity result = service.updateWorkflowStage(
                    REPORT_ID, ErrorReportWorkflowStage.INVESTIGATION_STARTED, ACTOR_ID);

            assertThat(result.getWorkflowStage()).isEqualTo(ErrorReportWorkflowStage.INVESTIGATION_STARTED);
            verify(activityService).record(eq(REPORT_ID), eq(ACTOR_ID),
                    eq(ErrorReportActivityType.WORKFLOW_CHANGED), isNull(), anyMap());
        }

        @Test
        @DisplayName("status=NEW かつ stage 非NULL は ERROR_REPORT_005 で失敗")
        void rejectsStageOnNewStatus() {
            ErrorReportEntity report = createReport(ErrorReportStatus.NEW, null);
            given(errorReportRepository.findById(REPORT_ID)).willReturn(Optional.of(report));

            assertThatThrownBy(() -> service.updateWorkflowStage(
                    REPORT_ID, ErrorReportWorkflowStage.INVESTIGATION_STARTED, ACTOR_ID))
                    .isInstanceOf(BusinessException.class)
                    .extracting("errorCode")
                    .isEqualTo(ErrorReportErrorCode.ERROR_REPORT_005);
        }

        @Test
        @DisplayName("status=RESOLVED かつ stage=FIX_IN_PROGRESS は ERROR_REPORT_005 で失敗")
        void rejectsInvalidStageOnResolved() {
            ErrorReportEntity report = createReport(ErrorReportStatus.RESOLVED, null);
            given(errorReportRepository.findById(REPORT_ID)).willReturn(Optional.of(report));

            assertThatThrownBy(() -> service.updateWorkflowStage(
                    REPORT_ID, ErrorReportWorkflowStage.FIX_IN_PROGRESS, ACTOR_ID))
                    .isInstanceOf(BusinessException.class);
        }

        @Test
        @DisplayName("status=RESOLVED かつ stage=RELEASED は許可")
        void allowsReleasedOnResolved() {
            ErrorReportEntity report = createReport(ErrorReportStatus.RESOLVED, null);
            given(errorReportRepository.findById(REPORT_ID)).willReturn(Optional.of(report));

            ErrorReportEntity result = service.updateWorkflowStage(
                    REPORT_ID, ErrorReportWorkflowStage.RELEASED, ACTOR_ID);

            assertThat(result.getWorkflowStage()).isEqualTo(ErrorReportWorkflowStage.RELEASED);
        }

        @Test
        @DisplayName("status=REOPENED かつ stage 非NULL は ERROR_REPORT_005 で失敗")
        void rejectsStageOnReopened() {
            ErrorReportEntity report = createReport(ErrorReportStatus.REOPENED, null);
            given(errorReportRepository.findById(REPORT_ID)).willReturn(Optional.of(report));

            assertThatThrownBy(() -> service.updateWorkflowStage(
                    REPORT_ID, ErrorReportWorkflowStage.INVESTIGATION_STARTED, ACTOR_ID))
                    .isInstanceOf(BusinessException.class);
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
}
