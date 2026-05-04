package com.mannschaft.app.shiftbudget.batch;

import com.mannschaft.app.shiftbudget.ShiftBudgetFailedEventStatus;
import com.mannschaft.app.shiftbudget.entity.ShiftBudgetFailedEventEntity;
import com.mannschaft.app.shiftbudget.repository.ShiftBudgetFailedEventRepository;
import com.mannschaft.app.shiftbudget.service.ShiftBudgetRetryExecutor;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.List;

/**
 * F08.7 Phase 10-β: 失敗イベントリトライバッチ。
 *
 * <p>15 分毎に PENDING / RETRYING ステータスを retry_count &lt; 3 で再実行する。
 * 3 回失敗で EXHAUSTED に遷移し、以降はバッチからは拾わない（管理 API で個別対応）。</p>
 *
 * <p>{@code feature.shift-budget.retry-batch-enabled=true} で有効化（default false、
 * 9-δ {@code monthly-close-cron-enabled} と同じ慎重運用）。</p>
 *
 * <p>ShedLock により複数インスタンス環境でも 1 回だけ実行されることを保証する。</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(prefix = "feature.shift-budget", name = "retry-batch-enabled",
        havingValue = "true")
public class ShiftBudgetRetryBatchJob {

    /** 1 バッチあたりの最大処理件数（過大バッチで他 API を圧迫しないよう制限）。 */
    private static final int BATCH_SIZE = 100;

    /** 最終リトライから次回再実行までの最低間隔（バックオフ）。 */
    private static final java.time.Duration RETRY_INTERVAL = java.time.Duration.ofMinutes(10);

    private final ShiftBudgetFailedEventRepository repository;
    private final ShiftBudgetRetryExecutor executor;

    /**
     * 15 分毎に実行する。
     */
    @Scheduled(cron = "0 */15 * * * ?", zone = "Asia/Tokyo")
    @SchedulerLock(name = "ShiftBudgetRetryBatchJob",
            lockAtMostFor = "10m", lockAtLeastFor = "1m")
    public void run() {
        LocalDateTime threshold = LocalDateTime.now().minus(RETRY_INTERVAL);
        log.info("F08.7 リトライバッチ起動: threshold={}", threshold);

        List<ShiftBudgetFailedEventEntity> targets = repository.findRetryablePending(
                List.of(ShiftBudgetFailedEventStatus.PENDING,
                        ShiftBudgetFailedEventStatus.RETRYING),
                threshold,
                PageRequest.of(0, BATCH_SIZE));

        int successCount = 0;
        int failedCount = 0;
        int exhaustedCount = 0;
        int skippedAlreadyMaxedCount = 0;
        for (ShiftBudgetFailedEventEntity entity : targets) {
            // 既に retry_count が上限を超えていれば EXHAUSTED 化のみ（execute 内では更に retry_count が
            // インクリメントされてしまうため事前にスキップ）
            if (entity.getRetryCount() != null && entity.getRetryCount() >= ShiftBudgetRetryExecutor.MAX_RETRY) {
                entity.markFailed(entity.getErrorMessage(), 0);  // maxRetry=0 → 即 EXHAUSTED へ
                repository.save(entity);
                exhaustedCount++;
                skippedAlreadyMaxedCount++;
                continue;
            }
            try {
                boolean success = executor.execute(entity);
                if (success) {
                    successCount++;
                } else {
                    if (entity.getRetryCount() != null
                            && entity.getRetryCount() >= ShiftBudgetRetryExecutor.MAX_RETRY) {
                        exhaustedCount++;
                    } else {
                        failedCount++;
                    }
                }
            } catch (Exception e) {
                // executor 自体の例外（DB 接続喪失など）— 1 件失敗で全体を止めない
                log.error("F08.7 リトライバッチ: executor 例外（処理継続）: id={}",
                        entity.getId(), e);
                failedCount++;
            }
        }

        log.info("F08.7 リトライバッチ完了: 対象={}, 成功={}, 失敗={}, EXHAUSTED={} (うち事前スキップ={})",
                targets.size(), successCount, failedCount, exhaustedCount, skippedAlreadyMaxedCount);
    }
}
