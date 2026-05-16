package com.mannschaft.app.errorreport.service;

import com.mannschaft.app.errorreport.ErrorReportSeverity;
import com.mannschaft.app.errorreport.entity.ErrorReportEntity;
import com.mannschaft.app.errorreport.repository.ErrorReportRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.StringRedisTemplate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
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
    @Mock
    private ErrorReportNotifier errorReportNotifier;
    @Mock
    private StringRedisTemplate redisTemplate;
    @Mock
    private ErrorReportAiAnalysisService aiAnalysisService;
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
}
