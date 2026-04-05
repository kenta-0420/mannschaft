package com.mannschaft.app.dashboard;

import com.mannschaft.app.dashboard.entity.ActivityFeedEntity;
import com.mannschaft.app.dashboard.repository.ActivityFeedRepository;
import com.mannschaft.app.dashboard.service.ActivitySummaryGenerator;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * アクティビティフィード書き込みイベントリスナー。
 * 各機能の Service が発行する ActivityEvent を非同期で受信し、activity_feed テーブルに INSERT する。
 * メインのトランザクションに影響させないよう、AFTER_COMMIT フェーズで @Async 実行する。
 * INSERT 失敗時はリトライせず WARN ログのみ出力する（30日で消えるデータのためコストに見合わない）。
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class ActivityFeedEventListener {

    private final ActivityFeedRepository activityFeedRepository;
    private final ActivitySummaryGenerator summaryGenerator;

    /**
     * アクティビティイベントを受信してフィードに書き込む。
     */
    @Async
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void handleActivityEvent(ActivityEvent event) {
        try {
            String summary = summaryGenerator.generate(event.getActivityType());

            ActivityFeedEntity entity = ActivityFeedEntity.builder()
                    .scopeType(event.getScopeType())
                    .scopeId(event.getScopeId())
                    .actorId(event.getActorId())
                    .activityType(event.getActivityType())
                    .targetType(event.getTargetType())
                    .targetId(event.getTargetId())
                    .summary(summary)
                    .build();

            activityFeedRepository.save(entity);

            log.debug("アクティビティフィード書き込み完了 activityType={}, scopeType={}, scopeId={}, actorId={}",
                    event.getActivityType(), event.getScopeType(), event.getScopeId(), event.getActorId());
        } catch (Exception e) {
            log.warn("アクティビティフィード書き込み失敗 activityType={}, scopeType={}, scopeId={}, actorId={}, error={}",
                    event.getActivityType(), event.getScopeType(), event.getScopeId(), event.getActorId(), e.getMessage(), e);
            // リトライは行わない（30日で消えるデータのためコストに見合わない）
        }
    }
}
