package com.mannschaft.app.todo.batch;

import com.mannschaft.app.admin.batch.BatchEndpoint;
import com.mannschaft.app.auth.service.AuditLogService;
import com.mannschaft.app.todo.entity.ProjectEntity;
import com.mannschaft.app.todo.entity.ProjectMilestoneEntity;
import com.mannschaft.app.todo.repository.ProjectMilestoneRepository;
import com.mannschaft.app.todo.repository.ProjectRepository;
import com.mannschaft.app.todo.repository.TodoRepository;
import com.mannschaft.app.todo.service.MilestoneGateService;
import com.mannschaft.app.todo.TodoStatus;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;

import java.math.BigDecimal;
import java.util.HashSet;
import java.util.Set;

/**
 * マイルストーン整合性バッチ（F02.7）。
 *
 * <p>毎日 03:15（JST）に全マイルストーンの非正規化フィールド（progress_rate）を
 * 再計算し、差分があれば補正する。またロック連鎖（is_locked / locked_by_milestone_id）
 * の整合性も補正対象プロジェクト単位で再評価する。</p>
 *
 * <p>設計書 §6.6 データ整合性 の方針:
 * {@code progress_rate} は非正規化フィールドだが、TODO ステータス変更時の原子的 UPDATE で
 * 整合性保証される。ただし競合・バグ・手動 DB 修正等による差異を検知・補正するため、
 * デイリーバッチで最終的な整合性を担保する。</p>
 *
 * <p>既存の類似バッチ（03:00 AuthCleanup / QuickMemoPhysicalDelete / ConfirmableExpiry、
 * 03:30 TagUsageCountReconcile / GamificationRanking）と時間帯を分離するため
 * 設計書通り 03:15 を採用。</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class MilestoneReconciliationBatch {

    /** 大量差分検知時のアラート閾値 */
    private static final int ALERT_THRESHOLD = 100;

    private final ProjectMilestoneRepository milestoneRepository;
    private final ProjectRepository projectRepository;
    private final TodoRepository todoRepository;
    private final MilestoneGateService milestoneGateService;
    private final AuditLogService auditLogService;

    /**
     * マイルストーン整合性を補正する。
     *
     * <ol>
     *   <li>全マイルストーンを走査し、配下 TODO の集計値から {@code progress_rate} を再計算</li>
     *   <li>現在値と差分がある場合のみ UPDATE</li>
     *   <li>削除済みプロジェクト配下は対象外</li>
     *   <li>差分を検出したプロジェクトについてはロック連鎖の再構築も実行（安全側のサニタイズ）</li>
     * </ol>
     *
     * <p>JST 03:15 実行。F02.3 の projects.progress_rate 補正バッチ（現状未実装）と
     * 同時間帯に配置して将来的に統合可能。</p>
     */
    @BatchEndpoint(name = "todo-milestone-reconciliation-daily", description = "マイルストーンの進捗率とロック連鎖を毎日 03:15 に再集計・補正する")
    @Scheduled(cron = "0 15 3 * * *", zone = "Asia/Tokyo")
    // 起動間隔は日次 03:15。全マイルストーンの進捗再集計とロック連鎖補正でマイルストーン数に比例する。余裕を取り 1 時間を上限とする。
    @SchedulerLock(name = "todoMilestoneReconciliationDaily", lockAtLeastFor = "PT1M", lockAtMostFor = "PT1H")
    @Transactional
    public void reconcile() {
        log.info("マイルストーン整合性バッチ開始");

        final int CHUNK_SIZE = 500;
        int checked = 0;
        int progressFixed = 0;
        Set<Long> chainRebuildTargetProjectIds = new HashSet<>();

        // 全マイルストーンをチャンク処理で走査（500件ずつページング取得）
        Pageable pageable = PageRequest.of(0, CHUNK_SIZE);
        Page<ProjectMilestoneEntity> milestonePage;
        do {
            milestonePage = milestoneRepository.findAll(pageable);
            for (ProjectMilestoneEntity milestone : milestonePage.getContent()) {
                // 削除済みプロジェクトのマイルストーンは対象外
                ProjectEntity project = projectRepository
                        .findByIdAndDeletedAtIsNull(milestone.getProjectId())
                        .orElse(null);
                if (project == null) {
                    continue;
                }
                checked++;

                long total = todoRepository.countByMilestoneIdAndDeletedAtIsNull(milestone.getId());
                long completed = todoRepository.countByMilestoneIdAndStatusAndDeletedAtIsNull(
                        milestone.getId(), TodoStatus.COMPLETED);

                BigDecimal currentRate = milestone.getProgressRate();
                milestone.updateProgressRate(total, completed);
                BigDecimal newRate = milestone.getProgressRate();

                if (currentRate == null || currentRate.compareTo(newRate) != 0) {
                    milestoneRepository.save(milestone);
                    progressFixed++;
                    chainRebuildTargetProjectIds.add(milestone.getProjectId());
                    log.debug("progress_rate 補正: milestoneId={}, projectId={}, old={}, new={}, total={}, completed={}",
                            milestone.getId(), milestone.getProjectId(),
                            currentRate, newRate, total, completed);
                }
            }
            pageable = pageable.next();
        } while (milestonePage.hasNext());

        // 差分が出たプロジェクトはロック連鎖も再評価（進捗率とロック状態の二重整合性保証）
        int chainsRebuilt = 0;
        for (Long projectId : chainRebuildTargetProjectIds) {
            try {
                milestoneGateService.rebuildChain(projectId);
                chainsRebuilt++;
            } catch (RuntimeException ex) {
                log.error("ロック連鎖再構築失敗: projectId={}", projectId, ex);
            }
        }

        if (progressFixed > ALERT_THRESHOLD) {
            log.error("マイルストーン整合性バッチ: 不整合件数が閾値超過 progressFixed={} (閾値={})",
                    progressFixed, ALERT_THRESHOLD);
        } else {
            log.info("マイルストーン整合性バッチ完了: checked={}, progressFixed={}, chainsRebuilt={}",
                    checked, progressFixed, chainsRebuilt);
        }

        // 監査ログに実行結果を記録（F10.3 連携）
        auditLogService.record(
                "MILESTONE_RECONCILE_BATCH",
                null, null, null, null, null, null, null,
                String.format("{\"checked\":%d,\"progressFixed\":%d,\"chainsRebuilt\":%d}",
                        checked, progressFixed, chainsRebuilt)
        );
    }
}
