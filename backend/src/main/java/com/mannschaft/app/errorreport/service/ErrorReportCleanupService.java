package com.mannschaft.app.errorreport.service;

import com.mannschaft.app.errorreport.ErrorReportStatus;
import com.mannschaft.app.errorreport.entity.ErrorReportEntity;
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
 * エラーレポートのクリーンアップバッチ。
 * 毎週日曜 AM3:00（JST）に実行し、古いレポートを物理削除または自動クローズする。
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class ErrorReportCleanupService {

    private final ErrorReportRepository errorReportRepository;
    private final StringRedisTemplate redisTemplate;

    @Scheduled(cron = "0 0 3 * * SUN", zone = "Asia/Tokyo")
    @SchedulerLock(name = "errorReportCleanup", lockAtMostFor = "PT20M", lockAtLeastFor = "PT1M")
    @Transactional
    public void cleanup() {
        LocalDateTime now = LocalDateTime.now();
        LocalDateTime threshold90 = now.minusDays(90);
        LocalDateTime threshold180 = now.minusDays(180);

        // 1. RESOLVED/IGNORED で updated_at が90日以上前 → 物理削除
        List<ErrorReportEntity> toDelete = errorReportRepository
                .findByStatusInAndUpdatedAtBefore(
                        List.of(ErrorReportStatus.RESOLVED, ErrorReportStatus.IGNORED), threshold90);

        if (!toDelete.isEmpty()) {
            // Valkey の affected キーも削除
            for (ErrorReportEntity report : toDelete) {
                try {
                    String key = "error-report:affected:" + report.getErrorHash();
                    redisTemplate.delete(key);
                } catch (Exception e) {
                    log.warn("Valkey キー削除失敗: hash={}", report.getErrorHash(), e);
                }
            }
            errorReportRepository.deleteAll(toDelete);
            log.info("[ErrorReportCleanup] 物理削除完了: {}件", toDelete.size());
        }

        // 2. NEW/REOPENED で last_occurred_at が180日以上前 → IGNORED に変更
        List<ErrorReportEntity> staleNewReopened = errorReportRepository
                .findByStatusInAndLastOccurredAtBefore(
                        List.of(ErrorReportStatus.NEW, ErrorReportStatus.REOPENED), threshold180);

        for (ErrorReportEntity report : staleNewReopened) {
            report.setStatus(ErrorReportStatus.IGNORED);
        }
        if (!staleNewReopened.isEmpty()) {
            log.info("[ErrorReportCleanup] NEW/REOPENED→IGNORED: {}件", staleNewReopened.size());
        }

        // 3. INVESTIGATING で updated_at が180日以上前 → IGNORED に変更 + admin_note に自動クローズ記録
        List<ErrorReportEntity> staleInvestigating = errorReportRepository
                .findByStatusAndUpdatedAtBefore(ErrorReportStatus.INVESTIGATING, threshold180);

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
            log.info("[ErrorReportCleanup] INVESTIGATING→IGNORED（自動クローズ）: {}件", staleInvestigating.size());
        }

        int totalProcessed = toDelete.size() + staleNewReopened.size() + staleInvestigating.size();
        if (totalProcessed == 0) {
            log.info("[ErrorReportCleanup] 対象レポートなし。スキップします");
        } else {
            log.info("[ErrorReportCleanup] クリーンアップ完了: 合計{}件処理", totalProcessed);
        }
    }
}
