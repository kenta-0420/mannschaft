package com.mannschaft.app.errorreport.service;

import com.mannschaft.app.admin.entity.BatchJobLogEntity;
import com.mannschaft.app.admin.service.BatchJobLogService;
import com.mannschaft.app.errorreport.ErrorReportStatus;
import com.mannschaft.app.errorreport.entity.ErrorReportEntity;
import com.mannschaft.app.errorreport.repository.ErrorReportAiAnalysisRepository;
import com.mannschaft.app.errorreport.repository.ErrorReportOccurrenceRepository;
import com.mannschaft.app.errorreport.repository.ErrorReportRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * F12.5 Phase 2-F — {@link ErrorReportCleanupService} の単体テスト。
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("ErrorReportCleanupService 単体テスト")
class ErrorReportCleanupServiceTest {

    @Mock
    private ErrorReportRepository errorReportRepository;
    @Mock
    private ErrorReportOccurrenceRepository occurrenceRepository;
    @Mock
    private ErrorReportAiAnalysisRepository aiAnalysisRepository;
    @Mock
    private BatchJobLogService batchJobLogService;
    @Mock
    private StringRedisTemplate redisTemplate;

    private ErrorReportCleanupService service;

    @BeforeEach
    void setUp() {
        service = new ErrorReportCleanupService(
                errorReportRepository,
                occurrenceRepository,
                aiAnalysisRepository,
                batchJobLogService,
                redisTemplate);

        BatchJobLogEntity logEntity = BatchJobLogEntity.builder().jobName("errorReportCleanup").build();
        lenient().when(batchJobLogService.startJob(anyString())).thenReturn(logEntity);

        // 既定では何も対象がない（個別テストで上書きする）
        lenient().when(occurrenceRepository.deleteByOccurredAtBefore(any())).thenReturn(0);
        lenient().when(aiAnalysisRepository.updateRawResponseToNullByCreatedAtBefore(any())).thenReturn(0);
        lenient().when(errorReportRepository.findByStatusInAndUpdatedAtBefore(any(), any()))
                .thenReturn(List.of());
        lenient().when(errorReportRepository.findByStatusInAndLastOccurredAtBefore(any(), any()))
                .thenReturn(List.of());
        lenient().when(errorReportRepository.findByStatusAndUpdatedAtBefore(any(), any()))
                .thenReturn(List.of());
    }

    @Test
    @DisplayName("正常系: 30日カットオフで occurrences 削除 / raw_response NULL化 が呼ばれる")
    void 正常_30日カットオフ() {
        LocalDateTime now = LocalDateTime.of(2026, 5, 6, 3, 0);
        given(occurrenceRepository.deleteByOccurredAtBefore(any())).willReturn(7);
        given(aiAnalysisRepository.updateRawResponseToNullByCreatedAtBefore(any())).willReturn(4);

        service.executeAt(now);

        ArgumentCaptor<LocalDateTime> occurrenceCutoff = ArgumentCaptor.forClass(LocalDateTime.class);
        verify(occurrenceRepository).deleteByOccurredAtBefore(occurrenceCutoff.capture());
        assertThat(occurrenceCutoff.getValue()).isEqualTo(now.minusDays(30));

        ArgumentCaptor<LocalDateTime> rawCutoff = ArgumentCaptor.forClass(LocalDateTime.class);
        verify(aiAnalysisRepository).updateRawResponseToNullByCreatedAtBefore(rawCutoff.capture());
        assertThat(rawCutoff.getValue()).isEqualTo(now.minusDays(30));

        // 7 + 4 = 11 件処理として完了が記録されること
        verify(batchJobLogService).completeJob(any(BatchJobLogEntity.class), eq(11));
    }

    @Test
    @DisplayName("正常系: RESOLVED/IGNORED 90日経過レポートが削除され Valkey キーも削除される")
    void 正常_クローズドレポート物理削除() {
        LocalDateTime now = LocalDateTime.of(2026, 5, 6, 3, 0);
        ErrorReportEntity report = ErrorReportEntity.builder()
                .errorHash("hash-1")
                .status(ErrorReportStatus.RESOLVED)
                .build();
        given(errorReportRepository.findByStatusInAndUpdatedAtBefore(any(), any()))
                .willReturn(List.of(report));

        service.executeAt(now);

        ArgumentCaptor<LocalDateTime> closedCutoff = ArgumentCaptor.forClass(LocalDateTime.class);
        verify(errorReportRepository)
                .findByStatusInAndUpdatedAtBefore(any(), closedCutoff.capture());
        assertThat(closedCutoff.getValue()).isEqualTo(now.minusDays(90));

        verify(redisTemplate).delete("error-report:affected:hash-1");
        verify(errorReportRepository).deleteAll(List.of(report));
    }

    @Test
    @DisplayName("正常系: NEW/REOPENED で 180日経過したものが IGNORED に変更される")
    void 正常_NEW_REOPENED_180日経過() {
        LocalDateTime now = LocalDateTime.of(2026, 5, 6, 3, 0);
        ErrorReportEntity report = ErrorReportEntity.builder()
                .errorHash("hash-2")
                .status(ErrorReportStatus.NEW)
                .build();
        given(errorReportRepository.findByStatusInAndLastOccurredAtBefore(any(), any()))
                .willReturn(List.of(report));

        service.executeAt(now);

        ArgumentCaptor<LocalDateTime> staleCutoff = ArgumentCaptor.forClass(LocalDateTime.class);
        verify(errorReportRepository)
                .findByStatusInAndLastOccurredAtBefore(any(), staleCutoff.capture());
        assertThat(staleCutoff.getValue()).isEqualTo(now.minusDays(180));
        assertThat(report.getStatus()).isEqualTo(ErrorReportStatus.IGNORED);
    }

    @Test
    @DisplayName("正常系: INVESTIGATING で 180日経過したものが IGNORED + 自動クローズ記録")
    void 正常_INVESTIGATING_自動クローズ() {
        LocalDateTime now = LocalDateTime.of(2026, 5, 6, 3, 0);
        ErrorReportEntity report = ErrorReportEntity.builder()
                .errorHash("hash-3")
                .status(ErrorReportStatus.INVESTIGATING)
                .adminNote("既存メモ")
                .build();
        given(errorReportRepository.findByStatusAndUpdatedAtBefore(any(), any()))
                .willReturn(List.of(report));

        service.executeAt(now);

        assertThat(report.getStatus()).isEqualTo(ErrorReportStatus.IGNORED);
        assertThat(report.getAdminNote()).contains("既存メモ").contains("180日間更新なしのため自動クローズ");
    }

    @Test
    @DisplayName("異常系: 例外発生時に failJob が呼ばれ例外が伝播する")
    void 異常_failJob() {
        LocalDateTime now = LocalDateTime.of(2026, 5, 6, 3, 0);
        given(occurrenceRepository.deleteByOccurredAtBefore(any()))
                .willThrow(new RuntimeException("DB error"));

        assertThatThrownBy(() -> service.executeAt(now))
                .isInstanceOf(RuntimeException.class)
                .hasMessage("DB error");

        verify(batchJobLogService).failJob(any(BatchJobLogEntity.class), eq("DB error"));
        verify(batchJobLogService, never()).completeJob(any(), anyInt());
    }

    @Test
    @DisplayName("正常系: 対象なしでも completeJob(0) が呼ばれる")
    void 正常_対象なしでも完了記録() {
        LocalDateTime now = LocalDateTime.of(2026, 5, 6, 3, 0);

        service.executeAt(now);

        verify(batchJobLogService).completeJob(any(BatchJobLogEntity.class), eq(0));
    }

}
