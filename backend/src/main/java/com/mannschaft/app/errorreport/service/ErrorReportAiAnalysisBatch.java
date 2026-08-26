package com.mannschaft.app.errorreport.service;

import com.mannschaft.app.admin.batch.BatchEndpoint;
import com.mannschaft.app.admin.entity.BatchJobLogEntity;
import com.mannschaft.app.admin.service.BatchJobLogService;
import com.mannschaft.app.errorreport.ErrorReportProperties;
import com.mannschaft.app.errorreport.entity.ErrorReportEntity;
import com.mannschaft.app.errorreport.repository.ErrorReportRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.List;

/**
 * F12.5 Phase 2-C — AI 自動分析バッチ。
 *
 * <p>5 分間隔で {@code last_ai_analysis_at IS NULL} かつ
 * {@code created_at < now - autoBatchDelayMinutes} のレポートを最大 20 件取得し、
 * AI 分析を実行する。1 件失敗しても残りを継続する。</p>
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class ErrorReportAiAnalysisBatch {

    /** 1 回のバッチで処理する最大件数。 */
    private static final int BATCH_SIZE = 20;

    private final ErrorReportRepository errorReportRepository;
    private final ErrorReportAiAnalysisService aiAnalysisService;
    private final BatchJobLogService batchJobLogService;
    private final ErrorReportProperties props;

    /**
     * バッチエントリポイント。{@code @Scheduled(fixedDelay = 300_000)}（5 分間隔）。
     */
    @BatchEndpoint(name = "errorreport-ai-analysis", description = "未分析エラーレポートを 5 分毎に AI 分析する")
    @Scheduled(fixedDelay = 300_000)
    @SchedulerLock(
            name = "errorReportAiAnalysisBatch",
            lockAtMostFor = "PT15M",
            lockAtLeastFor = "PT30S")
    public void execute() {
        executeAt(LocalDateTime.now());
    }

    /**
     * テストから直接呼び出せるエントリポイント。
     *
     * @param now 基準時刻
     */
    void executeAt(LocalDateTime now) {
        if (!props.getAi().isEnabled()) {
            log.debug("AI 分析バッチ スキップ: 機能無効");
            return;
        }
        BatchJobLogEntity logEntity = batchJobLogService.startJob("errorReportAiAnalysisBatch");
        int processed = 0;
        try {
            LocalDateTime cutoff = now.minusMinutes(props.getAi().getAutoBatchDelayMinutes());
            List<ErrorReportEntity> targets = errorReportRepository
                    .findByLastAiAnalysisAtIsNullAndCreatedAtBefore(
                            cutoff, PageRequest.of(0, BATCH_SIZE));
            for (ErrorReportEntity er : targets) {
                try {
                    aiAnalysisService.analyzeSync(er.getId(), null);
                    processed++;
                } catch (Exception e) {
                    log.warn("AI 分析失敗（バッチ継続）: errorReportId={}, error={}",
                            er.getId(), e.getMessage());
                }
            }
            batchJobLogService.completeJob(logEntity, processed);
        } catch (Exception e) {
            batchJobLogService.failJob(logEntity, e.getMessage());
            throw e;
        }
    }
}
