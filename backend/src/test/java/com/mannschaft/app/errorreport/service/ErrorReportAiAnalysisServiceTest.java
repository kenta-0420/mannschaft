package com.mannschaft.app.errorreport.service;

import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.errorreport.ErrorReportActivityType;
import com.mannschaft.app.errorreport.ErrorReportErrorCode;
import com.mannschaft.app.errorreport.ErrorReportProperties;
import com.mannschaft.app.errorreport.ErrorReportSeverity;
import com.mannschaft.app.errorreport.ErrorReportStatus;
import com.mannschaft.app.errorreport.entity.ErrorReportAiAnalysisEntity;
import com.mannschaft.app.errorreport.entity.ErrorReportEntity;
import com.mannschaft.app.errorreport.repository.ErrorReportAiAnalysisRepository;
import com.mannschaft.app.errorreport.repository.ErrorReportRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * F12.5 Phase 2-C — {@link ErrorReportAiAnalysisService} の単体テスト。
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("ErrorReportAiAnalysisService 単体テスト")
class ErrorReportAiAnalysisServiceTest {

    @Mock
    private ErrorReportRepository errorReportRepository;
    @Mock
    private ErrorReportAiAnalysisRepository aiAnalysisRepository;
    @Mock
    private ErrorReportClaudeAiProvider provider;
    @Mock
    private ErrorReportSanitizer sanitizer;
    @Mock
    private ErrorReportAiBudgetService budgetService;
    @Mock
    private ErrorReportActivityService activityService;
    @Mock
    private ErrorReportNotifier notifier;
    /** Issue #2990 L4 検分是正: FAILED 記録は独立TXの別 Bean が担う。 */
    @Mock
    private ErrorReportAiAnalysisFailureRecorder failureRecorder;

    private ErrorReportProperties props;

    private ErrorReportAiAnalysisService service;

    private static final Long REPORT_ID = 100L;
    private static final Long ACTOR_ID = 7L;

    @BeforeEach
    void setUp() {
        props = new ErrorReportProperties();
        props.getAi().setEnabled(true);
        props.getAi().setModel("claude-haiku-4-5");
        service = new ErrorReportAiAnalysisService(
                errorReportRepository, aiAnalysisRepository,
                provider, sanitizer, budgetService,
                activityService, notifier, props, failureRecorder);

        // sanitizer は素通しでよい（buildContext を呼ばないテストでは未使用）
        lenient().when(sanitizer.sanitize(any())).thenAnswer(inv -> inv.getArgument(0));
        lenient().when(sanitizer.sanitizePagePath(any())).thenAnswer(inv -> inv.getArgument(0));
    }

    private ErrorReportEntity sampleReport(ErrorReportSeverity severity) {
        return ErrorReportEntity.builder()
                .errorMessage("Test error")
                .pageUrl("/foo/123")
                .occurredAt(LocalDateTime.now())
                .status(ErrorReportStatus.NEW)
                .severity(severity)
                .errorHash("h")
                .occurrenceCount(3)
                .affectedUserCount(2)
                .firstOccurredAt(LocalDateTime.now().minusHours(1))
                .lastOccurredAt(LocalDateTime.now())
                .build();
    }

    @Test
    @DisplayName("AI 機能無効時は ERROR_REPORT_007")
    void analyzeSync_throwsWhenDisabled() {
        props.getAi().setEnabled(false);
        assertThatThrownBy(() -> service.analyzeSync(REPORT_ID, ACTOR_ID))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining(ErrorReportErrorCode.ERROR_REPORT_007.getMessage());
    }

    @Test
    @DisplayName("予算超過時は ERROR_REPORT_008")
    void analyzeSync_throwsWhenBudgetExceeded() {
        given(budgetService.canExpend(anyInt())).willReturn(false);

        assertThatThrownBy(() -> service.analyzeSync(REPORT_ID, ACTOR_ID))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining(ErrorReportErrorCode.ERROR_REPORT_008.getMessage());
    }

    @Test
    @DisplayName("SUCCESS: 分析履歴永続化 + last_ai_analysis_at 更新 + activity 記録")
    void analyzeSync_persistsSuccess() {
        ErrorReportEntity report = sampleReport(ErrorReportSeverity.HIGH);
        given(budgetService.canExpend(anyInt())).willReturn(true);
        given(errorReportRepository.findById(REPORT_ID)).willReturn(Optional.of(report));
        given(provider.analyze(any())).willReturn(AiAnalysisResult.builder()
                .estimatedCause("原因")
                .fixProposal("修正案")
                .impactAssessment("影響")
                .suggestedFiles(List.of("a.vue", "b.ts"))
                .promptTokens(100)
                .completionTokens(50)
                .rawResponse("{}")
                .build());
        given(aiAnalysisRepository.save(any())).willAnswer(inv -> inv.getArgument(0));

        ErrorReportAiAnalysisEntity entity = service.analyzeSync(REPORT_ID, ACTOR_ID);

        assertThat(entity.getStatus()).isEqualTo("SUCCESS");
        assertThat(entity.getEstimatedCause()).isEqualTo("原因");
        assertThat(entity.getSuggestedFiles()).isEqualTo("a.vue,b.ts");
        assertThat(report.getLastAiAnalysisAt()).isNotNull();

        verify(budgetService).recordExpense(anyInt());
        verify(activityService).record(eq(REPORT_ID), eq(ACTOR_ID),
                eq(ErrorReportActivityType.AI_ANALYZED), eq(null), anyMap());
    }

    @Test
    @DisplayName("CRITICAL レポートは AI 分析完了通知を送る")
    void analyzeSync_notifiesCriticalOnly() {
        ErrorReportEntity report = sampleReport(ErrorReportSeverity.CRITICAL);
        given(budgetService.canExpend(anyInt())).willReturn(true);
        given(errorReportRepository.findById(REPORT_ID)).willReturn(Optional.of(report));
        given(provider.analyze(any())).willReturn(AiAnalysisResult.builder()
                .estimatedCause("c").fixProposal("f").impactAssessment("i")
                .suggestedFiles(List.of()).promptTokens(50).completionTokens(20)
                .rawResponse("{}").build());
        given(aiAnalysisRepository.save(any())).willAnswer(inv -> inv.getArgument(0));

        service.analyzeSync(REPORT_ID, ACTOR_ID);

        verify(notifier).notifyAiAnalysisCompleted(eq(report), any());
    }

    @Test
    @DisplayName("HIGH レポートでは AI 分析完了通知を送らない（CRITICAL のみ）")
    void analyzeSync_doesNotNotifyHigh() {
        ErrorReportEntity report = sampleReport(ErrorReportSeverity.HIGH);
        given(budgetService.canExpend(anyInt())).willReturn(true);
        given(errorReportRepository.findById(REPORT_ID)).willReturn(Optional.of(report));
        given(provider.analyze(any())).willReturn(AiAnalysisResult.builder()
                .estimatedCause("c").fixProposal("f").impactAssessment("i")
                .suggestedFiles(List.of()).promptTokens(50).completionTokens(20)
                .rawResponse("{}").build());
        given(aiAnalysisRepository.save(any())).willAnswer(inv -> inv.getArgument(0));

        service.analyzeSync(REPORT_ID, ACTOR_ID);

        verify(notifier, never()).notifyAiAnalysisCompleted(any(), any());
    }

    @Test
    @DisplayName("FAILED: provider 例外時に FAILED レコードと last_ai_analysis_at が永続化される")
    void analyzeSync_persistsFailedOnException() {
        ErrorReportEntity report = sampleReport(ErrorReportSeverity.HIGH);
        given(budgetService.canExpend(anyInt())).willReturn(true);
        given(errorReportRepository.findById(REPORT_ID)).willReturn(Optional.of(report));
        given(provider.analyze(any()))
                .willThrow(new RuntimeException("API エラー"));

        assertThatThrownBy(() -> service.analyzeSync(REPORT_ID, ACTOR_ID))
                .isInstanceOf(RuntimeException.class);

        // Issue #2990 L4 検分是正: FAILED 記録は独立トランザクション（REQUIRES_NEW）の別 Bean へ委譲する。
        // 本メソッド内で直接 save していた是正前は、直後の throw で @Transactional が巻き戻すため
        // FAILED 行も last_ai_analysis_at も残らなかった。
        // ※ この単体テストはモックが save の巻き戻りを再現できないため、是正前でも緑になる
        //   （欠陥を隠していた検体）。実際の永続化は ErrorReportAiAnalysisFailureRecordIT が実DBで検証する。
        verify(failureRecorder).recordFailure(
                eq(REPORT_ID), eq("claude-haiku-4-5"), contains("API エラー"), eq(ACTOR_ID), any());
        verify(aiAnalysisRepository, never()).save(any());
        // activity は記録しない（FAILED 時）
        verify(activityService, never())
                .record(anyLong(), any(), any(), any(), any());
    }

    @Test
    @DisplayName("レポート未存在 → ERROR_REPORT_NOT_FOUND")
    void analyzeSync_throwsWhenReportMissing() {
        given(budgetService.canExpend(anyInt())).willReturn(true);
        given(errorReportRepository.findById(REPORT_ID)).willReturn(Optional.empty());

        assertThatThrownBy(() -> service.analyzeSync(REPORT_ID, ACTOR_ID))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining(ErrorReportErrorCode.ERROR_REPORT_NOT_FOUND.getMessage());
    }

    // Issue #2990 L4: AC-10（即時分析パスの予算チェック）の検体は analyzeAfterCommit の移設に伴い
    // ErrorReportAiAnalysisDispatcherTest へ移した。

    @Test
    @DisplayName("serializeSuggestedFiles: NULL は NULL を返す")
    void serializeSuggestedFiles_returnsNullForEmpty() {
        assertThat(service.serializeSuggestedFiles(null)).isNull();
        assertThat(service.serializeSuggestedFiles(List.of())).isNull();
    }

    @Test
    @DisplayName("serializeSuggestedFiles: 10件超は10件にクリップされる")
    void serializeSuggestedFiles_clipsAtTen() {
        List<String> many = java.util.stream.IntStream.rangeClosed(1, 15)
                .mapToObj(i -> "f" + i + ".vue").toList();
        String result = service.serializeSuggestedFiles(many);
        assertThat(result.split(",")).hasSize(10);
    }
}
