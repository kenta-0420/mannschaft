package com.mannschaft.app.errorreport.service;

import com.mannschaft.app.admin.batch.BatchEndpoint;
import com.mannschaft.app.admin.entity.BatchJobLogEntity;
import com.mannschaft.app.admin.service.BatchJobLogService;
import com.mannschaft.app.common.backgroundgate.BackgroundFeatureMode;
import com.mannschaft.app.common.backgroundgate.BackgroundFeaturePolicy;
import com.mannschaft.app.errorreport.ErrorReportStatus;
import com.mannschaft.app.errorreport.entity.ErrorReportEntity;
import com.mannschaft.app.errorreport.repository.ErrorReportAiAnalysisRepository;
import com.mannschaft.app.errorreport.repository.ErrorReportOccurrenceRepository;
import com.mannschaft.app.errorreport.repository.ErrorReportRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

/**
 * F12.5 エラーレポートのクリーンアップバッチ。
 *
 * <p>Phase 1 の集約レコード（{@code error_reports}）に加え、
 * Phase 2 で追加した個別発生ログ（{@code error_report_occurrences}）と
 * AI 分析履歴（{@code error_report_ai_analyses}）の保持期間管理も行う。</p>
 *
 * <ul>
 *   <li>{@code error_report_occurrences}: 30 日経過レコードを物理削除</li>
 *   <li>{@code error_report_ai_analyses.raw_response}: 30 日経過で NULL 化</li>
 *   <li>{@code error_reports}（RESOLVED / IGNORED）: 90 日経過で物理削除</li>
 *   <li>{@code error_reports}（NEW / REOPENED）: 180 日経過で IGNORED 化</li>
 *   <li>{@code error_reports}（INVESTIGATING）: 180 日経過で IGNORED + 自動クローズ記録</li>
 * </ul>
 *
 * <p>毎日 AM3:00（JST）に実行する。Phase 1 では毎週日曜だったが、Phase 2 で
 * occurrences の 30 日 / 100 件超過カットオフが日次必要なため日次実行に変更。</p>
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class ErrorReportCleanupService {

    private static final String JOB_NAME = "errorReportCleanup";
    private static final int OCCURRENCE_RETENTION_DAYS = 30;
    private static final int AI_RAW_RESPONSE_RETENTION_DAYS = 30;
    private static final int CLOSED_REPORT_RETENTION_DAYS = 90;
    private static final int STALE_REPORT_RETENTION_DAYS = 180;

    private final ErrorReportRepository errorReportRepository;
    private final ErrorReportOccurrenceRepository occurrenceRepository;
    private final ErrorReportAiAnalysisRepository aiAnalysisRepository;
    private final BatchJobLogService batchJobLogService;
    private final StringRedisTemplate redisTemplate;

    /**
     * 毎日 AM3:00（JST）に実行されるエントリポイント。
     */
    @BackgroundFeaturePolicy(mode = BackgroundFeatureMode.ALWAYS,
            reason = "対応する gate_key が無く停止条件を宣言できないため常時実行する。エラーレポート関連表の保持期間超過削除であり、再開後に同じ条件で拾い直せる。機能単位の閉栓が要るようになった時点で gate_key の発行から検討すること")
    @BatchEndpoint(name = "errorreport-cleanup-daily", description = "エラーレポート関連テーブルの保持期間超過レコードを毎日 03:00 にクリーンアップする")
    @Scheduled(cron = "0 0 3 * * *", zone = "Asia/Tokyo")
    @SchedulerLock(
            name = JOB_NAME,
            lockAtMostFor = "PT30M",
            lockAtLeastFor = "PT1M")
    public void cleanup() {
        executeAt(LocalDateTime.now());
    }

    /**
     * テスト容易性のため now を引数で受け取れるようにしたパッケージプライベート版。
     *
     * @param now 基準時刻（通常は {@link LocalDateTime#now()}）
     */
    @Transactional
    void executeAt(LocalDateTime now) {
        BatchJobLogEntity logEntity = batchJobLogService.startJob(JOB_NAME);
        int processed = 0;
        try {
            // 1. occurrences: 30 日経過レコードを物理削除
            LocalDateTime occurrenceCutoff = now.minusDays(OCCURRENCE_RETENTION_DAYS);
            int deletedOccurrences =
                    occurrenceRepository.deleteByOccurredAtBefore(occurrenceCutoff);
            if (deletedOccurrences > 0) {
                log.info("[ErrorReportCleanup] occurrences 物理削除: {}件", deletedOccurrences);
            }
            processed += deletedOccurrences;

            // 2. ai_analyses.raw_response: 30 日経過で NULL 化
            LocalDateTime rawResponseCutoff = now.minusDays(AI_RAW_RESPONSE_RETENTION_DAYS);
            int nullifiedRawResponses =
                    aiAnalysisRepository.updateRawResponseToNullByCreatedAtBefore(rawResponseCutoff);
            if (nullifiedRawResponses > 0) {
                log.info("[ErrorReportCleanup] ai_analyses.raw_response NULL化: {}件",
                        nullifiedRawResponses);
            }
            processed += nullifiedRawResponses;

            // 3. error_reports: RESOLVED / IGNORED で updated_at が 90 日以上前 → 物理削除
            LocalDateTime closedCutoff = now.minusDays(CLOSED_REPORT_RETENTION_DAYS);
            List<ErrorReportEntity> toDelete = errorReportRepository
                    .findByStatusInAndUpdatedAtBefore(
                            List.of(ErrorReportStatus.RESOLVED, ErrorReportStatus.IGNORED),
                            closedCutoff);
            if (!toDelete.isEmpty()) {
                for (ErrorReportEntity report : toDelete) {
                    try {
                        String key = "error-report:affected:" + report.getErrorHash();
                        redisTemplate.delete(key);
                    } catch (Exception e) {
                        log.warn("Valkey キー削除失敗: hash={}", report.getErrorHash(), e);
                    }
                }
                errorReportRepository.deleteAll(toDelete);
                log.info("[ErrorReportCleanup] error_reports 物理削除: {}件", toDelete.size());
            }
            processed += toDelete.size();

            // 4. error_reports: NEW / REOPENED で last_occurred_at が 180 日以上前 → IGNORED
            LocalDateTime staleCutoff = now.minusDays(STALE_REPORT_RETENTION_DAYS);
            List<ErrorReportEntity> staleNewReopened = errorReportRepository
                    .findByStatusInAndLastOccurredAtBefore(
                            List.of(ErrorReportStatus.NEW, ErrorReportStatus.REOPENED),
                            staleCutoff);
            for (ErrorReportEntity report : staleNewReopened) {
                report.setStatus(ErrorReportStatus.IGNORED);
            }
            if (!staleNewReopened.isEmpty()) {
                log.info("[ErrorReportCleanup] NEW/REOPENED→IGNORED: {}件",
                        staleNewReopened.size());
            }
            processed += staleNewReopened.size();

            // 5. error_reports: INVESTIGATING で updated_at が 180 日以上前 → IGNORED + 自動クローズ
            List<ErrorReportEntity> staleInvestigating = errorReportRepository
                    .findByStatusAndUpdatedAtBefore(
                            ErrorReportStatus.INVESTIGATING, staleCutoff);
            for (ErrorReportEntity report : staleInvestigating) {
                report.setStatus(ErrorReportStatus.IGNORED);
                String note = report.getAdminNote() != null ? report.getAdminNote() : "";
                if (!note.isEmpty()) {
                    note += "\n";
                }
                note += "180日間更新なしのため自動クローズ";
                report.setAdminNote(note);
            }
            if (!staleInvestigating.isEmpty()) {
                log.info("[ErrorReportCleanup] INVESTIGATING→IGNORED（自動クローズ）: {}件",
                        staleInvestigating.size());
            }
            processed += staleInvestigating.size();

            if (processed == 0) {
                log.info("[ErrorReportCleanup] 対象なし");
            } else {
                log.info("[ErrorReportCleanup] 完了: 合計{}件処理", processed);
            }
            batchJobLogService.completeJob(logEntity, processed);
        } catch (RuntimeException e) {
            log.error("[ErrorReportCleanup] 失敗", e);
            batchJobLogService.failJob(logEntity, e.getMessage());
            throw e;
        }
    }
}
