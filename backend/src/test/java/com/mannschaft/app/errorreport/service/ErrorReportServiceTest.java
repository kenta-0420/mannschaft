package com.mannschaft.app.errorreport.service;

import com.mannschaft.app.errorreport.ErrorReportSeverity;
import com.mannschaft.app.errorreport.ErrorReportStatus;
import com.mannschaft.app.errorreport.dto.ErrorReportRequest;
import com.mannschaft.app.errorreport.dto.ErrorReportUpdateRequest;
import com.mannschaft.app.errorreport.entity.ErrorReportEntity;
import com.mannschaft.app.errorreport.event.ErrorReportRaisedEvent;
import com.mannschaft.app.errorreport.event.ErrorReportRegressionDetectedEvent;
import com.mannschaft.app.errorreport.event.ErrorReportResolvedEvent;
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
import org.springframework.data.redis.core.StringRedisTemplate;

import java.time.LocalDateTime;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * {@link ErrorReportService} のコア機能（recordBackendException 公開 API）単体テスト。
 * ワークフロー / 担当者 / コメント / Kanban のテストはそれぞれ
 * {@link ErrorReportTimelineServiceTest} / {@link ErrorReportKanbanServiceTest} を参照。
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("ErrorReportService 単体テスト")
class ErrorReportServiceTest {

    @Mock
    private ErrorReportRepository errorReportRepository;
    /**
     * Issue #2990 L11 — 業務TX内で発火するのは通知ではなく業務イベントである。
     *
     * <p>是正前はここが {@code ErrorReportNotifier} のモックだったが、本テストは
     * <b>そのモックに対する verify を1本も持っていなかった</b>。つまり
     * {@code createOrAggregate} / {@code updateStatus} の通知発火は実装から丸ごと消しても
     * 緑のままだった。是正にあわせてイベント publish の検証を入れる。</p>
     */
    @Mock
    private ApplicationEventPublisher eventPublisher;
    @Mock
    private StringRedisTemplate redisTemplate;
    @Mock
    private ErrorReportAiAnalysisDispatcher aiAnalysisDispatcher;
    @Mock
    private ErrorReportAsyncExecutor asyncExecutor;

    @InjectMocks
    private ErrorReportService service;

    // ========================================
    // F10.6 Phase 10-β-1 — recordBackendException 公開 API（Executor 委譲）
    //
    // F10.5/F10.6 Phase 10-β 後続-⑥: 集約ロジック本体は ErrorReportAsyncExecutor に
    // 切り出された（@Async self-invocation バグ根治）。本テストでは公開 API が
    // Executor へ正しく委譲することのみを検証する。集約ロジックの単体テストは
    // {@link ErrorReportAsyncExecutorTest} を参照。
    // ========================================

    @Nested
    @DisplayName("recordBackendException 公開 API (F10.6 Phase 10-β-1 / 後続-⑥)")
    class RecordBackendException {

        @Test
        @DisplayName("F10.5/F10.6 後続-⑥: 公開 API recordBackendException(HttpServletRequest) は戻り値 null かつ Executor へ委譲する")
        void publicApi_returnsNull_andDelegatesToExecutor() {
            // 単体テストでは @Async は意味を持たないため、Executor をモック化して委譲のみ検証する。
            ErrorReportEntity result = service.recordBackendException(
                    new RuntimeException("e"), null, ErrorReportSeverity.MEDIUM);

            assertThat(result).isNull();
            verify(asyncExecutor).recordBackendException(
                    any(Throwable.class),
                    isNull(),    // pageUrl
                    isNull(),    // userAgent
                    isNull(),    // ipAddress
                    org.mockito.ArgumentMatchers.any(),  // requestId（MDC 経由、空なら null）
                    eq(ErrorReportSeverity.MEDIUM));
            // 集約・保存ロジックは Executor 内に移動済み: Service 側で Repository は呼ばない
            verify(errorReportRepository, never()).save(any(ErrorReportEntity.class));
        }

        @Test
        @DisplayName("F10.5/F10.6 後続-⑥: pageUrl 直渡しオーバーロードも Executor へそのまま委譲する")
        void publicApi_directOverload_delegatesToExecutor() {
            service.recordBackendException(
                    new RuntimeException("slow"),
                    "/api/v1/users/{id}",
                    "UA-1",
                    "203.0.113.1",
                    "rid-99",
                    ErrorReportSeverity.HIGH);

            verify(asyncExecutor).recordBackendException(
                    any(Throwable.class),
                    eq("/api/v1/users/{id}"),
                    eq("UA-1"),
                    eq("203.0.113.1"),
                    eq("rid-99"),
                    eq(ErrorReportSeverity.HIGH));
            verify(errorReportRepository, never()).save(any(ErrorReportEntity.class));
        }
    }

    // ========================================
    // Issue #2990 L11 — 業務TX内では通知ではなくイベントだけを publish する
    //
    // 是正前、本テストクラスは ErrorReportNotifier をモックしていながら verify を
    // 1本も持っておらず、createOrAggregate / updateStatus の通知発火は
    // 実装から消しても緑のままだった（既存テストが欠陥を隠していた実例）。
    // ========================================

    @Nested
    @DisplayName("createOrAggregate — 通知は業務イベントとして publish される（#2990 L11）")
    class CreateOrAggregateEvents {

        @Test
        @DisplayName("新規 HIGH: ErrorReportRaisedEvent(slackEnabled=true) を publish する")
        void newHighSeverity_publishesRaisedEvent() {
            given(errorReportRepository.findByErrorHash(org.mockito.ArgumentMatchers.anyString()))
                    .willReturn(Optional.empty());
            given(errorReportRepository.save(any(ErrorReportEntity.class)))
                    .willAnswer(inv -> ((ErrorReportEntity) inv.getArgument(0)).toBuilder().id(42L).build());

            ErrorReportEntity saved = service.createOrAggregate(ErrorReportRequest.builder()
                    .errorMessage("boom")
                    .pageUrl("https://example.com/checkout")
                    .occurredAt(LocalDateTime.now())
                    .build(), "203.0.113.9");

            assertThat(saved.getSeverity()).isEqualTo(ErrorReportSeverity.HIGH);
            ArgumentCaptor<ErrorReportRaisedEvent> captor =
                    ArgumentCaptor.forClass(ErrorReportRaisedEvent.class);
            verify(eventPublisher).publishEvent(captor.capture());
            assertThat(captor.getValue().reportId()).isEqualTo(42L);
            assertThat(captor.getValue().slackEnabled()).isTrue();
        }

        @Test
        @DisplayName("新規 MEDIUM: 通知イベントは publish されない（閾値未満）")
        void newMediumSeverity_publishesNothing() {
            given(errorReportRepository.findByErrorHash(org.mockito.ArgumentMatchers.anyString()))
                    .willReturn(Optional.empty());
            given(errorReportRepository.save(any(ErrorReportEntity.class)))
                    .willAnswer(inv -> ((ErrorReportEntity) inv.getArgument(0)).toBuilder().id(7L).build());

            service.createOrAggregate(ErrorReportRequest.builder()
                    .errorMessage("just a warning")
                    .pageUrl("https://example.com/home")
                    .occurredAt(LocalDateTime.now())
                    .build(), "203.0.113.9");

            verify(eventPublisher, never()).publishEvent(any(ErrorReportRaisedEvent.class));
        }

        @Test
        @DisplayName("RESOLVED と同一ハッシュ: ErrorReportRegressionDetectedEvent を publish する")
        void regression_publishesRegressionEvent() {
            ErrorReportEntity existing = report(11L, ErrorReportStatus.RESOLVED,
                    ErrorReportSeverity.MEDIUM, null);
            given(errorReportRepository.findByErrorHash(org.mockito.ArgumentMatchers.anyString()))
                    .willReturn(Optional.of(existing));
            lenient().when(redisTemplate.delete(org.mockito.ArgumentMatchers.anyString()))
                    .thenReturn(true);

            service.createOrAggregate(ErrorReportRequest.builder()
                    .errorMessage("boom")
                    .pageUrl("https://example.com/home")
                    .occurredAt(LocalDateTime.now())
                    .build(), "203.0.113.9");

            ArgumentCaptor<ErrorReportRegressionDetectedEvent> captor =
                    ArgumentCaptor.forClass(ErrorReportRegressionDetectedEvent.class);
            verify(eventPublisher).publishEvent(captor.capture());
            assertThat(captor.getValue().reportId()).isEqualTo(11L);
        }
    }

    @Nested
    @DisplayName("updateStatus — 解決通知は業務イベントとして publish される（#2990 L11）")
    class UpdateStatusEvents {

        @Test
        @DisplayName("RESOLVED かつ user_id 非NULL: ErrorReportResolvedEvent を publish する")
        void resolved_withReporter_publishesResolvedEvent() {
            ErrorReportEntity existing = report(31L, ErrorReportStatus.NEW,
                    ErrorReportSeverity.HIGH, 501L);
            given(errorReportRepository.findById(31L)).willReturn(Optional.of(existing));

            service.updateStatus(31L, new ErrorReportUpdateRequest("RESOLVED", null, null), 9L);

            ArgumentCaptor<ErrorReportResolvedEvent> captor =
                    ArgumentCaptor.forClass(ErrorReportResolvedEvent.class);
            verify(eventPublisher).publishEvent(captor.capture());
            assertThat(captor.getValue().reportId()).isEqualTo(31L);
        }

        @Test
        @DisplayName("RESOLVED でも user_id が NULL なら解決通知は publish されない（報告者不在）")
        void resolved_withoutReporter_publishesNothing() {
            ErrorReportEntity existing = report(32L, ErrorReportStatus.NEW,
                    ErrorReportSeverity.HIGH, null);
            given(errorReportRepository.findById(32L)).willReturn(Optional.of(existing));

            service.updateStatus(32L, new ErrorReportUpdateRequest("RESOLVED", null, null), 9L);

            verify(eventPublisher, never()).publishEvent(any(ErrorReportResolvedEvent.class));
        }

        @Test
        @DisplayName("RESOLVED 以外への更新では通知イベントを publish しない")
        void nonResolvedStatus_publishesNothing() {
            ErrorReportEntity existing = report(33L, ErrorReportStatus.NEW,
                    ErrorReportSeverity.HIGH, 501L);
            given(errorReportRepository.findById(33L)).willReturn(Optional.of(existing));

            service.updateStatus(33L, new ErrorReportUpdateRequest("INVESTIGATING", null, null), 9L);

            verify(eventPublisher, never()).publishEvent(any(ErrorReportResolvedEvent.class));
        }
    }

    /** テスト用のエラーレポート行を組み立てる。 */
    private static ErrorReportEntity report(Long id, ErrorReportStatus status,
                                            ErrorReportSeverity severity, Long userId) {
        LocalDateTime now = LocalDateTime.now();
        return ErrorReportEntity.builder()
                .id(id)
                .errorMessage("boom")
                .pageUrl("https://example.com/home")
                .userId(userId)
                .occurredAt(now)
                .status(status)
                .severity(severity)
                .errorHash("h-" + id)
                .occurrenceCount(1)
                .affectedUserCount(0)
                .firstOccurredAt(now)
                .lastOccurredAt(now)
                .build();
    }
}
