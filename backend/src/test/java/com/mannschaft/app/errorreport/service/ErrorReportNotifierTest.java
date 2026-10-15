package com.mannschaft.app.errorreport.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.mannschaft.app.common.i18n.UserLocaleCache;
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
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.MessageSource;
import org.springframework.context.support.ResourceBundleMessageSource;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.lenient;
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
    @Mock
    private UserLocaleCache userLocaleCache;
    @Mock
    private MessageSource messageSource;

    @InjectMocks
    private ErrorReportNotifier notifier;

    @BeforeEach
    void setUp() {
        // Slack Webhook URL は未設定とし、HTTP 副作用を排除する
        ReflectionTestUtils.setField(notifier, "slackWebhookUrl", "");
        ReflectionTestUtils.setField(notifier, "notifyThreshold", "HIGH");
        // Issue #2715 ロットC-1: 新規依存 UserLocaleCache/MessageSource の既定スタブ
        // （未スタブだと null 返却/NPE で通知が握りつぶされ、既存テストが偽装的に失敗する）。
        // 既存（i18n 非対象）テストはデフォルト文言との一致を検証したいので、
        // messageSource はそのままデフォルト文言（第3引数）を返すパススルーにする。
        lenient().when(userLocaleCache.getLocales(any())).thenReturn(Map.of());
        lenient().when(userLocaleCache.getLocale(anyLong())).thenReturn("ja");
        lenient().when(messageSource.getMessage(anyString(), any(), anyString(), any(Locale.class)))
                .thenAnswer(invocation -> invocation.getArgument(2));
    }

    /** 実物の MessageSource（messages*.properties）を差し込む（Issue #2715 ロットC-1 テスト方針）。 */
    private void useRealMessageSource() {
        ResourceBundleMessageSource realMessageSource = new ResourceBundleMessageSource();
        realMessageSource.setBasename("messages");
        realMessageSource.setDefaultEncoding("UTF-8");
        ReflectionTestUtils.setField(notifier, "messageSource", realMessageSource);
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

    // ============================================================
    // notifyAggregatedSummary (F10.6 §5.6-③)
    // ============================================================

    @org.junit.jupiter.api.Nested
    @DisplayName("notifyAggregatedSummary (F10.6 §5.6-③)")
    class NotifyAggregatedSummary {

        @Test
        @DisplayName("Slack URL 未設定時は writeValueAsString が呼ばれない")
        void noSlackUrl_skipsSerialization() throws Exception {
            ReflectionTestUtils.setField(notifier, "slackWebhookUrl", "");
            ErrorReportAggregator.AggregatedEntry entry = newEntry("h", "msg", ErrorReportSeverity.HIGH, 5L);

            notifier.notifyAggregatedSummary(java.util.Map.of("h", entry));

            org.mockito.Mockito.verify(objectMapper, never()).writeValueAsString(any());
        }

        @Test
        @DisplayName("空 Map の場合は writeValueAsString が呼ばれない")
        void emptyMap_skipsSerialization() throws Exception {
            ReflectionTestUtils.setField(notifier, "slackWebhookUrl", "https://example.invalid/hook");

            notifier.notifyAggregatedSummary(java.util.Map.of());
            notifier.notifyAggregatedSummary(null);

            org.mockito.Mockito.verify(objectMapper, never()).writeValueAsString(any());
        }

        @Test
        @DisplayName("Slack URL 設定済み + entries あり → JSON シリアライズが呼ばれる（HTTP は副作用扱い）")
        void withEntries_serializesPayload() throws Exception {
            ReflectionTestUtils.setField(notifier, "slackWebhookUrl", "https://example.invalid/hook");
            // HTTP 送信を発火させないため、シリアライズ段階で例外を投げて握らせる
            given(objectMapper.writeValueAsString(any())).willThrow(new RuntimeException("stop"));

            notifier.notifyAggregatedSummary(java.util.Map.of(
                    "h1", newEntry("h1", "msg1", ErrorReportSeverity.HIGH, 5L),
                    "h2", newEntry("h2", "msg2", ErrorReportSeverity.MEDIUM, 3L)));

            org.mockito.Mockito.verify(objectMapper, times(1)).writeValueAsString(any());
        }
    }

    /**
     * AggregatedEntry をリフレクションで生成する（package-private コンストラクタ + recordOccurrence）。
     */
    private static ErrorReportAggregator.AggregatedEntry newEntry(
            String hash, String msg, ErrorReportSeverity sev, long count) throws Exception {
        java.lang.reflect.Constructor<ErrorReportAggregator.AggregatedEntry> ctor =
                ErrorReportAggregator.AggregatedEntry.class
                        .getDeclaredConstructor(String.class, String.class, ErrorReportSeverity.class, java.time.Instant.class);
        ctor.setAccessible(true);
        ErrorReportAggregator.AggregatedEntry e = ctor.newInstance(hash, msg, sev, java.time.Instant.now());
        for (long i = 1; i < count; i++) {
            java.lang.reflect.Method m = ErrorReportAggregator.AggregatedEntry.class
                    .getDeclaredMethod("recordOccurrence", java.time.Instant.class);
            m.setAccessible(true);
            m.invoke(e, java.time.Instant.now());
        }
        return e;
    }

    // ============================================================
    // Issue #2715 CMP-055 ロットC-1: 通知本文の i18n
    // ============================================================

    @org.junit.jupiter.api.Nested
    @DisplayName("Issue #2715 ロットC-1: 通知本文の locale 別組み立て")
    class LocalizedNotificationBody {

        @Test
        @DisplayName("notifySystemAdmins: 受信者 locale が en なら件名・本文が英語になり、locale 解決は getLocales 1回のみ（N+1防止）")
        void notifySystemAdmins_en_localizesAndResolvesLocalesInBulk() {
            useRealMessageSource();
            ErrorReportEntity report = sampleReport(ErrorReportSeverity.CRITICAL);
            ReflectionTestUtils.setField(report, "id", 100L);

            given(userRoleRepository.findSystemAdminUserIds()).willReturn(List.of(1L, 2L));
            given(userLocaleCache.getLocales(List.of(1L, 2L)))
                    .willReturn(Map.of(1L, "en", 2L, "en"));

            notifier.notifySystemAdmins(report);

            ArgumentCaptor<String> titleCaptor = ArgumentCaptor.forClass(String.class);
            ArgumentCaptor<String> bodyCaptor = ArgumentCaptor.forClass(String.class);
            verify(notificationService, times(2)).createNotification(
                    anyLong(), eq("ERROR_REPORT_CRITICAL"), eq(NotificationPriority.HIGH),
                    titleCaptor.capture(), bodyCaptor.capture(),
                    eq("ERROR_REPORT"), eq(100L),
                    eq(NotificationScopeType.SYSTEM), eq(null),
                    anyString(), eq(null));
            assertThat(titleCaptor.getValue()).isEqualTo("Frontend error (CRITICAL)");
            assertThat(bodyCaptor.getValue()).doesNotContain("{0}").doesNotContain("{1}")
                    .contains("3").contains("TypeError");

            // AC-3: N+1 防止 — バルク解決 (getLocales) は 1 回、単体解決 (getLocale) は 0 回。
            verify(userLocaleCache, times(1)).getLocales(any());
            verify(userLocaleCache, never()).getLocale(anyLong());
        }

        @Test
        @DisplayName("notifyEscalation: 受信者 locale が en なら件名・本文が英語になる")
        void notifyEscalation_en() {
            useRealMessageSource();
            ErrorReportEntity report = sampleReport(ErrorReportSeverity.CRITICAL);
            ReflectionTestUtils.setField(report, "id", 100L);
            ReflectionTestUtils.setField(notifier, "slackWebhookUrl", "");

            given(userRoleRepository.findSystemAdminUserIds()).willReturn(List.of(1L));
            given(userLocaleCache.getLocales(List.of(1L))).willReturn(Map.of(1L, "en"));

            notifier.notifyEscalation(report, ErrorReportSeverity.MEDIUM, ErrorReportSeverity.CRITICAL);

            ArgumentCaptor<String> titleCaptor = ArgumentCaptor.forClass(String.class);
            ArgumentCaptor<String> bodyCaptor = ArgumentCaptor.forClass(String.class);
            verify(notificationService).createNotification(
                    eq(1L), eq("ERROR_REPORT_ESCALATION"), eq(NotificationPriority.HIGH),
                    titleCaptor.capture(), bodyCaptor.capture(),
                    eq("ERROR_REPORT"), eq(100L),
                    eq(NotificationScopeType.SYSTEM), eq(null),
                    anyString(), eq(null));
            assertThat(titleCaptor.getValue()).isEqualTo("Error severity escalated from MEDIUM to CRITICAL");
            assertThat(bodyCaptor.getValue()).doesNotContain("{0}").doesNotContain("{1}");
        }

        @Test
        @DisplayName("notifyRegression: 受信者 locale が en なら件名・本文が英語になる")
        void notifyRegression_en() {
            useRealMessageSource();
            ErrorReportEntity report = sampleReport(ErrorReportSeverity.HIGH);
            ReflectionTestUtils.setField(report, "id", 100L);
            ReflectionTestUtils.setField(notifier, "slackWebhookUrl", "");

            given(userRoleRepository.findSystemAdminUserIds()).willReturn(List.of(1L));
            given(userLocaleCache.getLocales(List.of(1L))).willReturn(Map.of(1L, "en"));

            notifier.notifyRegression(report);

            ArgumentCaptor<String> titleCaptor = ArgumentCaptor.forClass(String.class);
            ArgumentCaptor<String> bodyCaptor = ArgumentCaptor.forClass(String.class);
            verify(notificationService).createNotification(
                    eq(1L), eq("ERROR_REPORT_REGRESSION"), eq(NotificationPriority.HIGH),
                    titleCaptor.capture(), bodyCaptor.capture(),
                    eq("ERROR_REPORT"), eq(100L),
                    eq(NotificationScopeType.SYSTEM), eq(null),
                    anyString(), eq(null));
            assertThat(titleCaptor.getValue()).isEqualTo("A resolved error has recurred");
            assertThat(bodyCaptor.getValue()).doesNotContain("{0}").contains("TypeError");
        }

        @Test
        @DisplayName("notifyAssignment: 担当者 locale が en なら件名が英語になる")
        void notifyAssignment_en() {
            useRealMessageSource();
            ErrorReportEntity report = sampleReport(ErrorReportSeverity.HIGH);
            ReflectionTestUtils.setField(report, "id", 100L);
            given(userLocaleCache.getLocale(99L)).willReturn("en");

            notifier.notifyAssignment(report, 99L);

            ArgumentCaptor<String> titleCaptor = ArgumentCaptor.forClass(String.class);
            verify(notificationService).createNotification(
                    eq(99L), eq("ERROR_REPORT_ASSIGNED"), eq(NotificationPriority.NORMAL),
                    titleCaptor.capture(), anyString(),
                    eq("ERROR_REPORT"), eq(100L),
                    eq(NotificationScopeType.PERSONAL), eq(null),
                    eq("/system-admin/error-reports/100"), eq(null));
            assertThat(titleCaptor.getValue()).isEqualTo("An error report has been assigned to you");
        }

        @Test
        @DisplayName("notifyAiAnalysisCompleted: 受信者 locale が en なら件名が英語になる")
        void notifyAiAnalysisCompleted_en() {
            useRealMessageSource();
            ErrorReportEntity report = sampleReport(ErrorReportSeverity.CRITICAL);
            ReflectionTestUtils.setField(report, "id", 100L);
            ErrorReportAiAnalysisEntity analysis = ErrorReportAiAnalysisEntity.builder()
                    .errorReportId(100L)
                    .modelName("claude-haiku-4-5")
                    .estimatedCause("null チェック漏れ")
                    .status("SUCCESS")
                    .build();
            given(userRoleRepository.findSystemAdminUserIds()).willReturn(List.of(1L));
            given(userLocaleCache.getLocales(List.of(1L))).willReturn(Map.of(1L, "en"));

            notifier.notifyAiAnalysisCompleted(report, analysis);

            ArgumentCaptor<String> titleCaptor = ArgumentCaptor.forClass(String.class);
            verify(notificationService).createNotification(
                    eq(1L), eq("ERROR_REPORT_AI_ANALYZED"), eq(NotificationPriority.NORMAL),
                    titleCaptor.capture(), anyString(),
                    eq("ERROR_REPORT"), eq(100L),
                    eq(NotificationScopeType.SYSTEM), eq(null),
                    anyString(), eq(null));
            assertThat(titleCaptor.getValue()).isEqualTo("AI analysis of the error report is complete");
        }

        @Test
        @DisplayName("notifyBudgetWarning: 受信者 locale が en なら件名・本文が英語になる")
        void notifyBudgetWarning_en() {
            useRealMessageSource();
            given(userRoleRepository.findSystemAdminUserIds()).willReturn(List.of(1L));
            given(userLocaleCache.getLocales(List.of(1L))).willReturn(Map.of(1L, "en"));

            notifier.notifyBudgetWarning(5000, 4000L);

            ArgumentCaptor<String> titleCaptor = ArgumentCaptor.forClass(String.class);
            ArgumentCaptor<String> bodyCaptor = ArgumentCaptor.forClass(String.class);
            verify(notificationService).createNotification(
                    eq(1L), eq("ERROR_REPORT_AI_BUDGET"), eq(NotificationPriority.HIGH),
                    titleCaptor.capture(), bodyCaptor.capture(),
                    eq("ERROR_REPORT"), eq(null),
                    eq(NotificationScopeType.SYSTEM), eq(null),
                    anyString(), eq(null));
            assertThat(titleCaptor.getValue()).isEqualTo("AI monthly budget reached 80%");
            assertThat(bodyCaptor.getValue()).doesNotContain("{0}").doesNotContain("{1}")
                    .contains("5000").contains("4000");
        }

        @Test
        @DisplayName("notifyAiHealthDegraded: 受信者 locale が en なら件名・本文が英語になる")
        void notifyAiHealthDegraded_en() {
            useRealMessageSource();
            given(userRoleRepository.findSystemAdminUserIds()).willReturn(List.of(1L));
            given(userLocaleCache.getLocales(List.of(1L))).willReturn(Map.of(1L, "en"));

            notifier.notifyAiHealthDegraded(7L);

            ArgumentCaptor<String> titleCaptor = ArgumentCaptor.forClass(String.class);
            ArgumentCaptor<String> bodyCaptor = ArgumentCaptor.forClass(String.class);
            verify(notificationService).createNotification(
                    eq(1L), eq("ERROR_REPORT_AI_HEALTH"), eq(NotificationPriority.HIGH),
                    titleCaptor.capture(), bodyCaptor.capture(),
                    eq("ERROR_REPORT"), eq(null),
                    eq(NotificationScopeType.SYSTEM), eq(null),
                    anyString(), eq(null));
            assertThat(titleCaptor.getValue()).isEqualTo("AI analysis service anomaly detected");
            assertThat(bodyCaptor.getValue()).doesNotContain("{0}").contains("7");
        }

        @Test
        @DisplayName("notifyResolution: 報告者 locale が en なら件名・本文が英語になる")
        void notifyResolution_en() {
            useRealMessageSource();
            ErrorReportEntity report = sampleReport(ErrorReportSeverity.MEDIUM);
            ReflectionTestUtils.setField(report, "id", 100L);
            ReflectionTestUtils.setField(report, "userId", 50L);
            given(userLocaleCache.getLocale(50L)).willReturn("en");

            notifier.notifyResolution(report);

            ArgumentCaptor<String> titleCaptor = ArgumentCaptor.forClass(String.class);
            ArgumentCaptor<String> bodyCaptor = ArgumentCaptor.forClass(String.class);
            verify(notificationService).createNotification(
                    eq(50L), eq("ERROR_REPORT_RESOLVED"), eq(NotificationPriority.NORMAL),
                    titleCaptor.capture(), bodyCaptor.capture(),
                    eq("ERROR_REPORT"), eq(100L),
                    eq(NotificationScopeType.PERSONAL), eq(null),
                    eq(null), eq(null));
            assertThat(titleCaptor.getValue()).isEqualTo("The issue you reported has been resolved");
            assertThat(bodyCaptor.getValue()).doesNotContain("{0}").contains("TypeError");
        }
    }

    // ============================================================
    // Issue #2990 L11 — 途中失敗: 1人の受信者への失敗が他へ波及しない
    //
    // 是正前は管理者ループ全体が1つの try に入っていたため、1人目への
    // createNotification が落ちると残りの管理者全員が通知を受け取れなかった。
    // ============================================================

    @org.junit.jupiter.api.Nested
    @DisplayName("受信者ごとの被害半径（#2990 L11）")
    class PerRecipientIsolation {

        @Test
        @DisplayName("notifySystemAdmins: 1人目の通知が落ちても残りの管理者へ配送は続く")
        void notifySystemAdmins_continuesAfterOneRecipientFails() {
            given(userRoleRepository.findSystemAdminUserIds()).willReturn(List.of(1L, 2L, 3L));
            org.mockito.BDDMockito.willThrow(new RuntimeException("INSERT 失敗"))
                    .given(notificationService).createNotification(
                            eq(1L), anyString(), any(), anyString(), anyString(),
                            anyString(), any(), any(), any(), anyString(), any());

            notifier.notifySystemAdmins(sampleReport(ErrorReportSeverity.CRITICAL));

            // 1人目が落ちても 2人目・3人目には届く（合計3回試行される）
            verify(notificationService, times(3)).createNotification(
                    anyLong(), anyString(), any(), anyString(), anyString(),
                    anyString(), any(), any(), any(), anyString(), any());
        }

        @Test
        @DisplayName("notifyEscalation: 1人目の通知が落ちても残りの管理者へ配送は続く")
        void notifyEscalation_continuesAfterOneRecipientFails() {
            given(userRoleRepository.findSystemAdminUserIds()).willReturn(List.of(1L, 2L, 3L));
            org.mockito.BDDMockito.willThrow(new RuntimeException("INSERT 失敗"))
                    .given(notificationService).createNotification(
                            eq(1L), anyString(), any(), anyString(), anyString(),
                            anyString(), any(), any(), any(), anyString(), any());

            notifier.notifyEscalation(sampleReport(ErrorReportSeverity.CRITICAL),
                    ErrorReportSeverity.HIGH, ErrorReportSeverity.CRITICAL);

            verify(notificationService, times(3)).createNotification(
                    anyLong(), anyString(), any(), anyString(), anyString(),
                    anyString(), any(), any(), any(), anyString(), any());
        }

        @Test
        @DisplayName("notifyRegression: 1人目の通知が落ちても残りの管理者へ配送は続く")
        void notifyRegression_continuesAfterOneRecipientFails() {
            given(userRoleRepository.findSystemAdminUserIds()).willReturn(List.of(1L, 2L, 3L));
            org.mockito.BDDMockito.willThrow(new RuntimeException("INSERT 失敗"))
                    .given(notificationService).createNotification(
                            eq(1L), anyString(), any(), anyString(), anyString(),
                            anyString(), any(), any(), any(), anyString(), any());

            notifier.notifyRegression(sampleReport(ErrorReportSeverity.CRITICAL));

            verify(notificationService, times(3)).createNotification(
                    anyLong(), anyString(), any(), anyString(), anyString(),
                    anyString(), any(), any(), any(), anyString(), any());
        }

        @Test
        @DisplayName("notifySystemAdmins: 受信者が0名でも例外を投げず、通知は1件も出ない")
        void notifySystemAdmins_emptyRecipients() {
            given(userRoleRepository.findSystemAdminUserIds()).willReturn(List.of());

            notifier.notifySystemAdmins(sampleReport(ErrorReportSeverity.CRITICAL));

            verify(notificationService, never()).createNotification(
                    anyLong(), anyString(), any(), anyString(), anyString(),
                    anyString(), any(), any(), any(), any(), any());
        }

        @Test
        @DisplayName("notifySystemAdmins: 受信者リストの解決自体が落ちたら配送を中止し例外は伝播しない")
        void notifySystemAdmins_recipientResolutionFailure() {
            given(userRoleRepository.findSystemAdminUserIds())
                    .willThrow(new RuntimeException("DB down"));

            notifier.notifySystemAdmins(sampleReport(ErrorReportSeverity.CRITICAL));

            verify(notificationService, never()).createNotification(
                    anyLong(), anyString(), any(), anyString(), anyString(),
                    anyString(), any(), any(), any(), any(), any());
        }
    }
}
