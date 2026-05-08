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

    // ============================================================
    // notifySlowRequest（F10.5 Phase 10-β / F10.6 Phase 10-β-1）
    // ============================================================

    @org.junit.jupiter.api.Nested
    @DisplayName("notifySlowRequest（F10.5 Phase 10-β）")
    class NotifySlowRequest {

        @Test
        @DisplayName("Slack Webhook URL 未設定時は何もしない")
        void slackUrl_blank_skips() {
            ReflectionTestUtils.setField(notifier, "slackWebhookUrl", "");

            notifier.notifySlowRequest("GET", "/api/v1/todos", 12345L, "rid");

            // ObjectMapper も呼ばれない
            org.mockito.Mockito.verifyNoInteractions(objectMapper);
        }

        @Test
        @DisplayName("Slack Webhook URL 設定時は ObjectMapper でペイロードがシリアライズされる")
        void slackUrl_set_serializesPayload() throws Exception {
            // RestClient へのアクセスで HTTP 例外を起こすと困るので、ObjectMapper の
            // writeValueAsString が例外を投げることで RestClient 呼び出し前で抜けることを利用する。
            ReflectionTestUtils.setField(notifier, "slackWebhookUrl", "https://example.invalid/hook");
            given(objectMapper.writeValueAsString(any())).willThrow(new RuntimeException("stop here"));

            notifier.notifySlowRequest("GET", "/api/v1/todos", 12345L, "rid");

            // 例外は内部 catch で握り潰され、テストは正常終了
            org.mockito.Mockito.verify(objectMapper).writeValueAsString(any());
        }

        @Test
        @DisplayName("クールダウン: 同一 method+path で 2 回目以降は ObjectMapper が呼ばれない")
        void cooldown_suppressesDuplicateNotifications() throws Exception {
            ReflectionTestUtils.setField(notifier, "slackWebhookUrl", "https://example.invalid/hook");
            given(objectMapper.writeValueAsString(any())).willThrow(new RuntimeException("stop here"));

            notifier.notifySlowRequest("GET", "/api/v1/todos", 12345L, "rid-1");
            notifier.notifySlowRequest("GET", "/api/v1/todos", 23456L, "rid-2");

            // 2 回目はクールダウンでスキップされ、ObjectMapper は 1 回のみ
            org.mockito.Mockito.verify(objectMapper, times(1)).writeValueAsString(any());
        }

        @Test
        @DisplayName("クールダウン: method または path が異なれば別キーとして通知される")
        void cooldown_isPerMethodAndPath() throws Exception {
            ReflectionTestUtils.setField(notifier, "slackWebhookUrl", "https://example.invalid/hook");
            given(objectMapper.writeValueAsString(any())).willThrow(new RuntimeException("stop here"));

            notifier.notifySlowRequest("GET", "/api/v1/todos", 12345L, null);
            notifier.notifySlowRequest("POST", "/api/v1/todos", 12345L, null);
            notifier.notifySlowRequest("GET", "/api/v1/teams", 12345L, null);

            org.mockito.Mockito.verify(objectMapper, times(3)).writeValueAsString(any());
        }
    }

    // ============================================================
    // notifyHealthDown（F10.5 Phase 10-β / F10.6 Phase 10-β-1）
    // ============================================================

    @org.junit.jupiter.api.Nested
    @DisplayName("notifyHealthDown（F10.5 Phase 10-β）")
    class NotifyHealthDown {

        @Test
        @DisplayName("Slack Webhook URL 未設定時は何もしない")
        void slackUrl_blank_skips() {
            ReflectionTestUtils.setField(notifier, "slackWebhookUrl", "");

            notifier.notifyHealthDown("db", "Connection refused");

            org.mockito.Mockito.verifyNoInteractions(objectMapper);
        }

        @Test
        @DisplayName("クールダウン: 同一 component で 2 回目はスキップ")
        void cooldown_suppressesDuplicate() throws Exception {
            ReflectionTestUtils.setField(notifier, "slackWebhookUrl", "https://example.invalid/hook");
            given(objectMapper.writeValueAsString(any())).willThrow(new RuntimeException("stop here"));

            notifier.notifyHealthDown("db", "Connection refused");
            notifier.notifyHealthDown("db", "Connection refused (2nd)");

            org.mockito.Mockito.verify(objectMapper, times(1)).writeValueAsString(any());
        }

        @Test
        @DisplayName("クールダウン: 異なる component は別キーとして通知される")
        void cooldown_isPerComponent() throws Exception {
            ReflectionTestUtils.setField(notifier, "slackWebhookUrl", "https://example.invalid/hook");
            given(objectMapper.writeValueAsString(any())).willThrow(new RuntimeException("stop here"));

            notifier.notifyHealthDown("db", "down-1");
            notifier.notifyHealthDown("redis", "down-2");

            org.mockito.Mockito.verify(objectMapper, times(2)).writeValueAsString(any());
        }
    }
}
