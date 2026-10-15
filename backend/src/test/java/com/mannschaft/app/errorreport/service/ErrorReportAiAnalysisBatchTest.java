package com.mannschaft.app.errorreport.service;

import com.mannschaft.app.admin.entity.BatchJobLogEntity;
import com.mannschaft.app.admin.service.BatchJobLogService;
import com.mannschaft.app.errorreport.ErrorReportProperties;
import com.mannschaft.app.errorreport.ErrorReportSeverity;
import com.mannschaft.app.errorreport.ErrorReportStatus;
import com.mannschaft.app.errorreport.entity.ErrorReportEntity;
import com.mannschaft.app.errorreport.repository.ErrorReportRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Pageable;

import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

/**
 * F12.5 Phase 2-C — {@link ErrorReportAiAnalysisBatch} の単体テスト。
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("ErrorReportAiAnalysisBatch 単体テスト")
class ErrorReportAiAnalysisBatchTest {

    @Mock
    private ErrorReportRepository errorReportRepository;
    @Mock
    private ErrorReportAiAnalysisService aiAnalysisService;
    @Mock
    private BatchJobLogService batchJobLogService;

    private ErrorReportProperties props;
    private ErrorReportAiAnalysisBatch batch;

    @BeforeEach
    void setUp() {
        props = new ErrorReportProperties();
        props.getAi().setEnabled(true);
        props.getAi().setAutoBatchDelayMinutes(30);
        batch = new ErrorReportAiAnalysisBatch(
                errorReportRepository, aiAnalysisService,
                batchJobLogService, props);
    }

    private ErrorReportEntity sample(Long id) {
        ErrorReportEntity e = ErrorReportEntity.builder()
                .errorMessage("e")
                .pageUrl("/x")
                .occurredAt(LocalDateTime.now())
                .status(ErrorReportStatus.NEW)
                .severity(ErrorReportSeverity.MEDIUM)
                .errorHash("h")
                .occurrenceCount(1)
                .affectedUserCount(1)
                .firstOccurredAt(LocalDateTime.now())
                .lastOccurredAt(LocalDateTime.now())
                .build();
        // id を強制的に設定（リフレクション）
        try {
            java.lang.reflect.Field idField = e.getClass().getSuperclass().getDeclaredField("id");
            idField.setAccessible(true);
            idField.set(e, id);
        } catch (Exception ex) {
            throw new RuntimeException(ex);
        }
        return e;
    }

    @Test
    @DisplayName("AI 機能無効時は早期 return（startJob も呼ばない）")
    void executeAt_skipsWhenAiDisabled() {
        props.getAi().setEnabled(false);

        batch.executeAt(LocalDateTime.now());

        verify(batchJobLogService, never()).startJob(anyString());
        verify(errorReportRepository, never())
                .findByLastAiAnalysisAtIsNullAndCreatedAtBefore(any(), any());
    }

    @Test
    @DisplayName("cutoff = now - autoBatchDelayMinutes で対象を抽出する")
    void executeAt_passesCorrectCutoff() {
        LocalDateTime now = LocalDateTime.of(2026, 5, 6, 12, 0);
        BatchJobLogEntity logEntity = org.mockito.Mockito.mock(BatchJobLogEntity.class);
        given(batchJobLogService.startJob(anyString())).willReturn(logEntity);
        given(errorReportRepository.findByLastAiAnalysisAtIsNullAndCreatedAtBefore(any(), any()))
                .willReturn(List.of());

        batch.executeAt(now);

        ArgumentCaptor<LocalDateTime> cutoffCaptor =
                ArgumentCaptor.forClass(LocalDateTime.class);
        verify(errorReportRepository).findByLastAiAnalysisAtIsNullAndCreatedAtBefore(
                cutoffCaptor.capture(), any(Pageable.class));
        assertThat(cutoffCaptor.getValue()).isEqualTo(now.minusMinutes(30));
        verify(batchJobLogService).completeJob(eq(logEntity), eq(0));
    }

    @Test
    @DisplayName("1 件失敗しても残りを継続する")
    void executeAt_continuesAfterIndividualFailure() {
        BatchJobLogEntity logEntity = org.mockito.Mockito.mock(BatchJobLogEntity.class);
        given(batchJobLogService.startJob(anyString())).willReturn(logEntity);

        ErrorReportEntity r1 = sample(1L);
        ErrorReportEntity r2 = sample(2L);
        ErrorReportEntity r3 = sample(3L);
        given(errorReportRepository.findByLastAiAnalysisAtIsNullAndCreatedAtBefore(any(), any()))
                .willReturn(List.of(r1, r2, r3));

        // r2 だけ失敗
        org.mockito.Mockito.doAnswer(inv -> {
            Long id = inv.getArgument(0);
            if (id == 2L) throw new RuntimeException("boom");
            return null;
        }).when(aiAnalysisService).analyzeSync(any(), any());

        batch.executeAt(LocalDateTime.now());

        verify(aiAnalysisService, times(3)).analyzeSync(any(), any());
        // 成功 2 件で completeJob
        verify(batchJobLogService).completeJob(eq(logEntity), eq(2));
    }

    @Test
    @DisplayName("レポート抽出例外時は failJob 呼出 + 例外伝播")
    void executeAt_failsJobOnRepositoryException() {
        BatchJobLogEntity logEntity = org.mockito.Mockito.mock(BatchJobLogEntity.class);
        given(batchJobLogService.startJob(anyString())).willReturn(logEntity);
        given(errorReportRepository.findByLastAiAnalysisAtIsNullAndCreatedAtBefore(any(), any()))
                .willThrow(new RuntimeException("DB down"));

        try {
            batch.executeAt(LocalDateTime.now());
        } catch (RuntimeException ignored) { }

        verify(batchJobLogService).failJob(eq(logEntity), eq("DB down"));
    }
}
