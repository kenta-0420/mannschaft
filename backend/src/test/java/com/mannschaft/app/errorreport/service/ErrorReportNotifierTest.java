package com.mannschaft.app.errorreport.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.mannschaft.app.errorreport.ErrorReportSeverity;
import com.mannschaft.app.errorreport.ErrorReportStatus;
import com.mannschaft.app.errorreport.entity.ErrorReportAiAnalysisEntity;
import com.mannschaft.app.errorreport.entity.ErrorReportEntity;
import com.mannschaft.app.notification.NotificationPriority;
import com.mannschaft.app.notification.NotificationScopeType;
import com.mannschaft.app.notification.service.NotificationService;
import com.mannschaft.app.role.repository.UserRoleRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.LocalDateTime;
import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

/**
 * F12.5 Phase 2-G — {@link ErrorReportNotifier} の単体テスト。
 *
 * <p>P2-B〜P2-F で追加された通知メソッドを対象に、
 * NotificationService / UserRoleRepository への呼び出しが期待通り行われることを検証する。
 * Slack Webhook 経由の HTTP 呼び出し（RestClient）は副作用としてのカバレッジは検証せず、
 * URL 未設定時に余計な通知が走らないことを保証する。</p>
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("ErrorReportNotifier 単体テスト")
class ErrorReportNotifierTest {

    @Mock
    private ObjectMapper objectMapper;
    @Mock
    private NotificationService notificationService;
    @Mock
    private UserRoleRepository userRoleRepository;

    @InjectMocks
    private ErrorReportNotifier notifier;

    @BeforeEach
    void setUp() {
        // Slack Webhook URL は未設定とし、HTTP 副作用を排除する
        ReflectionTestUtils.setField(notifier, "slackWebhookUrl", "");
        ReflectionTestUtils.setField(notifier, "notifyThreshold", "HIGH");
    }

    private ErrorReportEntity sampleReport(ErrorReportSeverity severity) {
        return ErrorReportEntity.builder()
                .errorMessage("TypeError: Cannot read property of null")
                .pageUrl("/page")
                .occurredAt(LocalDateTime.now())
                .status(ErrorReportStatus.NEW)
                .severity(severity)
                .errorHash("h")
                .occurrenceCount(3)
                .affectedUserCount(2)
                .firstOccurredAt(LocalDateTime.now())
                .lastOccurredAt(LocalDateTime.now())
                .build();
    }

    // ============================================================
    // notifyAssignment（P2-B）
    // ============================================================

    @Test
    @DisplayName("notifyAssignment: 担当者が NULL の場合は通知しない")
    void notifyAssignment_skipsWhenAssigneeNull() {
        ErrorReportEntity report = sampleReport(ErrorReportSeverity.HIGH);
        ReflectionTestUtils.setField(report, "id", 100L);

        notifier.notifyAssignment(report, null);

        verify(notificationService, never()).createNotification(
                anyLong(), anyString(), any(), anyString(), anyString(),
                anyString(), any(), any(), any(), any(), any());
    }

    @Test
    @DisplayName("notifyAssignment: 担当者にプッシュ通知（PERSONAL スコープ）が送信される")
    void notifyAssignment_createsPersonalNotification() {
        ErrorReportEntity report = sampleReport(ErrorReportSeverity.HIGH);
        ReflectionTestUtils.setField(report, "id", 100L);

        notifier.notifyAssignment(report, 99L);

        verify(notificationService).createNotification(
                eq(99L), eq("ERROR_REPORT_ASSIGNED"), eq(NotificationPriority.NORMAL),
                anyString(), anyString(),
                eq("ERROR_REPORT"), eq(100L),
                eq(NotificationScopeType.PERSONAL), eq(null),
                eq("/system-admin/error-reports/100"), eq(null)
        );
    }

    // ============================================================
    // notifyAiAnalysisCompleted（P2-C）
    // ============================================================

    @Test
    @DisplayName("notifyAiAnalysisCompleted: SYSTEM_ADMIN プッシュ通知が送信される")
    void notifyAiAnalysisCompleted_notifiesAdmins() throws Exception {
        ErrorReportEntity report = sampleReport(ErrorReportSeverity.CRITICAL);
        ReflectionTestUtils.setField(report, "id", 100L);
        ErrorReportAiAnalysisEntity analysis = ErrorReportAiAnalysisEntity.builder()
                .errorReportId(100L)
                .modelName("claude-haiku-4-5")
                .estimatedCause("null チェック漏れ")
                .status("SUCCESS")
                .build();
        given(userRoleRepository.findSystemAdminUserIds()).willReturn(List.of(1L, 2L));

        notifier.notifyAiAnalysisCompleted(report, analysis);

        verify(notificationService, times(2)).createNotification(
                anyLong(), eq("ERROR_REPORT_AI_ANALYZED"), eq(NotificationPriority.NORMAL),
                anyString(), anyString(),
                eq("ERROR_REPORT"), eq(100L),
                eq(NotificationScopeType.SYSTEM), eq(null),
                anyString(), eq(null)
        );
    }

    // ============================================================
    // notifyBudgetWarning / notifyBudgetExceeded（P2-C）
    // ============================================================

    @Test
    @DisplayName("notifyBudgetWarning: SYSTEM_ADMIN へ HIGH 優先度通知が送信される")
    void notifyBudgetWarning_notifiesAdmins() {
        given(userRoleRepository.findSystemAdminUserIds()).willReturn(List.of(1L));

        notifier.notifyBudgetWarning(5000, 4000L);

        verify(notificationService).createNotification(
                eq(1L), eq("ERROR_REPORT_AI_BUDGET"), eq(NotificationPriority.HIGH),
                anyString(), anyString(),
                eq("ERROR_REPORT"), eq(null),
                eq(NotificationScopeType.SYSTEM), eq(null),
                anyString(), eq(null)
        );
    }

    @Test
    @DisplayName("notifyBudgetExceeded: SYSTEM_ADMIN へ HIGH 優先度通知が送信される")
    void notifyBudgetExceeded_notifiesAdmins() {
        given(userRoleRepository.findSystemAdminUserIds()).willReturn(List.of(1L, 2L));

        notifier.notifyBudgetExceeded(5000, 5500L);

        verify(notificationService, times(2)).createNotification(
                anyLong(), eq("ERROR_REPORT_AI_BUDGET"), eq(NotificationPriority.HIGH),
                anyString(), anyString(),
                eq("ERROR_REPORT"), eq(null),
                eq(NotificationScopeType.SYSTEM), eq(null),
                anyString(), eq(null)
        );
    }

    // ============================================================
    // notifyAiHealthDegraded（P2-F）
    // ============================================================

    @Test
    @DisplayName("notifyAiHealthDegraded: SYSTEM_ADMIN へ AI ヘルス劣化通知が送信される")
    void notifyAiHealthDegraded_notifiesAdmins() {
        given(userRoleRepository.findSystemAdminUserIds()).willReturn(List.of(1L));

        notifier.notifyAiHealthDegraded(7L);

        verify(notificationService).createNotification(
                eq(1L), eq("ERROR_REPORT_AI_HEALTH"), eq(NotificationPriority.HIGH),
                anyString(), anyString(),
                eq("ERROR_REPORT"), eq(null),
                eq(NotificationScopeType.SYSTEM), eq(null),
                anyString(), eq(null)
        );
    }

    // ============================================================
    // notifyResolution（P1 互換、user_id 非NULL のみ）
    // ============================================================

    @Test
    @DisplayName("notifyResolution: user_id が NULL の場合は通知しない")
    void notifyResolution_skipsWhenUserIdNull() {
        ErrorReportEntity report = sampleReport(ErrorReportSeverity.MEDIUM);
        ReflectionTestUtils.setField(report, "id", 100L);
        // userId 未設定（NULL）

        notifier.notifyResolution(report);

        verify(notificationService, never()).createNotification(
                anyLong(), anyString(), any(), anyString(), anyString(),
                anyString(), any(), any(), any(), any(), any());
    }

    @Test
    @DisplayName("notifyResolution: user_id 非 NULL 時に PERSONAL 通知が送信される")
    void notifyResolution_notifiesReporter() {
        ErrorReportEntity report = sampleReport(ErrorReportSeverity.MEDIUM);
        ReflectionTestUtils.setField(report, "id", 100L);
        ReflectionTestUtils.setField(report, "userId", 50L);

        notifier.notifyResolution(report);

        verify(notificationService).createNotification(
                eq(50L), eq("ERROR_REPORT_RESOLVED"), eq(NotificationPriority.NORMAL),
                anyString(), anyString(),
                eq("ERROR_REPORT"), eq(100L),
                eq(NotificationScopeType.PERSONAL), eq(null),
                eq(null), eq(null)
        );
    }
}
