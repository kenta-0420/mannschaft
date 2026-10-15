package com.mannschaft.app.errorreport.service;

import com.mannschaft.app.errorreport.ErrorReportSeverity;
import com.mannschaft.app.errorreport.ErrorReportStatus;
import com.mannschaft.app.errorreport.entity.ErrorReportEntity;
import com.mannschaft.app.errorreport.event.ErrorReportRaisedEvent;
import com.mannschaft.app.errorreport.event.ErrorReportRegressionDetectedEvent;
import com.mannschaft.app.errorreport.repository.ErrorReportRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.ArgumentCaptor;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;

import java.time.LocalDateTime;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * F10.5/F10.6 Phase 10-β 後続-⑥ — {@link ErrorReportAsyncExecutor} の単体テスト。
 *
 * <p>本テストは @Async self-invocation バグ根治で {@link ErrorReportService} から
 * 切り出された Executor の集約ロジック（重複検知 / リグレッション / Slack 通知判定）を検証する。
 * 旧 {@link ErrorReportServiceTest} の {@code RecordBackendException} Nested クラスから
 * ロジック検証分を引き継いだもの。</p>
 *
 * <p>@Async プロキシ越しの「別スレッド実行」確認は {@link ErrorReportAsyncExecutorAsyncIntegrationTest}
 * （@SpringBootTest）で行う。本ファイルは Mockito 単体テストとして純粋な集約ロジックのみ確認する。</p>
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("ErrorReportAsyncExecutor 単体テスト (F10.5/F10.6 Phase 10-β 後続-⑥)")
class ErrorReportAsyncExecutorTest {

    @Mock
    private ErrorReportRepository errorReportRepository;
    /**
     * Issue #2990 L11 — 業務TX内で発火するのは通知ではなく業務イベントである。
     * 是正前はここが {@code ErrorReportNotifier} のモックだった。
     */
    @Mock
    private ApplicationEventPublisher eventPublisher;
    /** F10.6 §5.6-③ — 集約バッファ。テストでは Mock を注入し、addOccurrence の呼び出し回数を検証する。 */
    @Mock
    private ErrorReportAggregator aggregator;

    @InjectMocks
    private ErrorReportAsyncExecutor executor;

    @Nested
    @DisplayName("doRecordBackendException — 集約コア")
    class DoRecordBackendException {

        @Test
        @DisplayName("新規例外: status=NEW で error_reports に保存され、HIGH 以上は Slack 通知される")
        void newException_savedAsNewAndNotifiedWhenHigh() {
            given(errorReportRepository.findByErrorHash(org.mockito.ArgumentMatchers.anyString()))
                    .willReturn(Optional.empty());
            given(errorReportRepository.save(any(ErrorReportEntity.class)))
                    .willAnswer(inv -> inv.getArgument(0));
            // F10.6 §5.6-③ — 新規発火は FIRST_OCCURRENCE を返す（即時 Slack 通知が走る分岐）
            given(aggregator.addOccurrence(org.mockito.ArgumentMatchers.anyString(),
                    org.mockito.ArgumentMatchers.any(),
                    org.mockito.ArgumentMatchers.any()))
                    .willReturn(ErrorReportAggregator.AggregationResult.FIRST_OCCURRENCE);

            RuntimeException ex = new RuntimeException("boom");
            ErrorReportEntity result = executor.doRecordBackendException(
                    ex, null, null, null, null, ErrorReportSeverity.HIGH);

            assertThat(result.getStatus()).isEqualTo(ErrorReportStatus.NEW);
            assertThat(result.getSeverity()).isEqualTo(ErrorReportSeverity.HIGH);
            assertThat(result.getErrorMessage()).contains("RuntimeException");
            assertThat(result.getErrorMessage()).contains("boom");
            assertThat(result.getErrorHash()).isNotBlank();
            assertThat(result.getErrorHash()).hasSize(64); // SHA-256 hex
            assertThat(result.getOccurrenceCount()).isEqualTo(1);
            assertThat(result.getAffectedUserCount()).isZero();
            // HIGH 以上は ErrorReportRaisedEvent を publish（FIRST_OCCURRENCE のとき slackEnabled=true）
            ArgumentCaptor<ErrorReportRaisedEvent> captor =
                    ArgumentCaptor.forClass(ErrorReportRaisedEvent.class);
            verify(eventPublisher).publishEvent(captor.capture());
            assertThat(captor.getValue().slackEnabled()).isTrue();
        }

        @Test
        @DisplayName("F10.6 §5.6-③: 新規 HIGH でも Aggregator が BUFFERED を返したら Slack 即時通知は抑制される")
        void newException_high_butBuffered_skipsSlack() {
            given(errorReportRepository.findByErrorHash(org.mockito.ArgumentMatchers.anyString()))
                    .willReturn(Optional.empty());
            given(errorReportRepository.save(any(ErrorReportEntity.class)))
                    .willAnswer(inv -> inv.getArgument(0));
            given(aggregator.addOccurrence(org.mockito.ArgumentMatchers.anyString(),
                    org.mockito.ArgumentMatchers.any(),
                    org.mockito.ArgumentMatchers.any()))
                    .willReturn(ErrorReportAggregator.AggregationResult.BUFFERED);

            executor.doRecordBackendException(
                    new RuntimeException("boom"), null, null, null, null, ErrorReportSeverity.HIGH);

            // Slack 抑制（5分毎の集約サマリで送信される）、SYSTEM_ADMIN プッシュは埋没防止のため維持。
            // 抑制の表現はイベントの slackEnabled=false であり、イベント自体は必ず publish される。
            ArgumentCaptor<ErrorReportRaisedEvent> captor =
                    ArgumentCaptor.forClass(ErrorReportRaisedEvent.class);
            verify(eventPublisher).publishEvent(captor.capture());
            assertThat(captor.getValue().slackEnabled()).isFalse();
        }

        @Test
        @DisplayName("MEDIUM severity の新規例外は Slack/SYSTEM_ADMIN 通知が走らない")
        void newException_notNotifiedWhenMedium() {
            given(errorReportRepository.findByErrorHash(org.mockito.ArgumentMatchers.anyString()))
                    .willReturn(Optional.empty());
            given(errorReportRepository.save(any(ErrorReportEntity.class)))
                    .willAnswer(inv -> inv.getArgument(0));

            executor.doRecordBackendException(new IllegalArgumentException("bad"),
                    null, null, null, null, ErrorReportSeverity.MEDIUM);

            verify(eventPublisher, never()).publishEvent(any(ErrorReportRaisedEvent.class));
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
                ErrorReportEntity saved = executor.doRecordBackendException(
                        new RuntimeException("x"), null, null, null, null, ErrorReportSeverity.MEDIUM);
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

            ErrorReportEntity r1 = executor.doRecordBackendException(ex1, null, null, null, null, ErrorReportSeverity.LOW);
            ErrorReportEntity r2 = executor.doRecordBackendException(ex2, null, null, null, null, ErrorReportSeverity.LOW);

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
        @DisplayName("pageUrl/userAgent/ipAddress 直渡し: error_reports に正しく保存される（後続-④）")
        void requestFields_areExtracted() {
            given(errorReportRepository.findByErrorHash(org.mockito.ArgumentMatchers.anyString()))
                    .willReturn(Optional.empty());
            given(errorReportRepository.save(any(ErrorReportEntity.class)))
                    .willAnswer(inv -> inv.getArgument(0));

            ErrorReportEntity saved = executor.doRecordBackendException(
                    new RuntimeException("e"),
                    "/api/v1/foo",
                    "UA-1",
                    "203.0.113.1",
                    null,
                    ErrorReportSeverity.MEDIUM);

            assertThat(saved.getPageUrl()).isEqualTo("/api/v1/foo");
            assertThat(saved.getUserAgent()).isEqualTo("UA-1");
            assertThat(saved.getIpAddress()).isEqualTo("203.0.113.1");
        }

        @Test
        @DisplayName("F10.5/F10.6 後続-④: pageUrl が null/空のときは 'backend' フォールバック")
        void pageUrl_fallbackToBackend_whenNull() {
            given(errorReportRepository.findByErrorHash(org.mockito.ArgumentMatchers.anyString()))
                    .willReturn(Optional.empty());
            given(errorReportRepository.save(any(ErrorReportEntity.class)))
                    .willAnswer(inv -> inv.getArgument(0));

            ErrorReportEntity saved = executor.doRecordBackendException(
                    new RuntimeException("e"), null, null, null, null,
                    ErrorReportSeverity.MEDIUM);

            assertThat(saved.getPageUrl()).isEqualTo("backend");
        }

        @Test
        @DisplayName("F10.5/F10.6 後続-④: URI テンプレート pageUrl が保存される（slow request 想定）")
        void pageUrl_templateIsPersisted() {
            given(errorReportRepository.findByErrorHash(org.mockito.ArgumentMatchers.anyString()))
                    .willReturn(Optional.empty());
            given(errorReportRepository.save(any(ErrorReportEntity.class)))
                    .willAnswer(inv -> inv.getArgument(0));

            ErrorReportEntity saved = executor.doRecordBackendException(
                    new RuntimeException("slow"),
                    "/api/v1/users/{id}",
                    null, null, "rid-99", ErrorReportSeverity.HIGH);

            assertThat(saved.getPageUrl()).isEqualTo("/api/v1/users/{id}");
            assertThat(saved.getRequestId()).isEqualTo("rid-99");
        }

        @Test
        @DisplayName("F10.6 §5.6-③: 既存 NEW レコードへの重複集約は Aggregator.addOccurrence(BUFFERED 想定) を呼ぶ")
        void existing_active_increments_aggregator() {
            ErrorReportEntity existing = ErrorReportEntity.builder()
                    .errorMessage("dup")
                    .pageUrl("/p")
                    .occurredAt(LocalDateTime.now())
                    .status(ErrorReportStatus.NEW)
                    .severity(ErrorReportSeverity.MEDIUM)
                    .errorHash("h")
                    .occurrenceCount(2)
                    .affectedUserCount(1)
                    .firstOccurredAt(LocalDateTime.now())
                    .lastOccurredAt(LocalDateTime.now())
                    .build();
            given(errorReportRepository.findByErrorHash(org.mockito.ArgumentMatchers.anyString()))
                    .willReturn(Optional.of(existing));

            executor.doRecordBackendException(
                    new RuntimeException("dup"), null, null, null, null, ErrorReportSeverity.MEDIUM);

            // Aggregator が呼ばれることだけ検証（戻り値は BUFFERED 想定だが厳密判定は本体テストに譲る）
            verify(aggregator).addOccurrence(
                    org.mockito.ArgumentMatchers.anyString(),
                    org.mockito.ArgumentMatchers.any(),
                    org.mockito.ArgumentMatchers.any());
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

            ErrorReportEntity result = executor.doRecordBackendException(
                    new RuntimeException("re"), null, null, null, null, ErrorReportSeverity.HIGH);

            assertThat(result.getStatus()).isEqualTo(ErrorReportStatus.REOPENED);
            verify(eventPublisher).publishEvent(any(ErrorReportRegressionDetectedEvent.class));
        }
    }

    @Nested
    @DisplayName("recordBackendException — @Async ラッパー（同期テスト時挙動）")
    class RecordBackendExceptionWrapper {

        @Test
        @DisplayName("単体テスト（プロキシなし）でも recordBackendException は doRecordBackendException 経由で save が走る")
        void wrapperInvokesCore_inUnitTest() {
            given(errorReportRepository.findByErrorHash(org.mockito.ArgumentMatchers.anyString()))
                    .willReturn(Optional.empty());
            given(errorReportRepository.save(any(ErrorReportEntity.class)))
                    .willAnswer(inv -> inv.getArgument(0));

            // 単体テストでは @Async はプロキシが無いため、wrapper は同期で core を呼ぶ。
            executor.recordBackendException(
                    new RuntimeException("e"), null, null, null, null, ErrorReportSeverity.MEDIUM);

            verify(errorReportRepository, org.mockito.Mockito.atLeastOnce())
                    .save(any(ErrorReportEntity.class));
        }

        @Test
        @DisplayName("コア処理が例外を投げても呼び出し側スレッドへ伝搬しない（warn ログのみ）")
        void wrapperSwallowsException() {
            given(errorReportRepository.findByErrorHash(org.mockito.ArgumentMatchers.anyString()))
                    .willThrow(new RuntimeException("DB down"));

            // 例外が投げ返されないこと（@Async スレッド内で握って warn ログのみ残す挙動）
            executor.recordBackendException(
                    new RuntimeException("e"), null, null, null, null, ErrorReportSeverity.MEDIUM);
        }
    }
}
