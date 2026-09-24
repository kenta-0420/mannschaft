package com.mannschaft.app.notification.confirmable.service;

import com.mannschaft.app.admin.batch.BatchEndpoint;
import com.mannschaft.app.common.backgroundgate.BackgroundFeatureMode;
import com.mannschaft.app.common.backgroundgate.BackgroundFeaturePolicy;
import com.mannschaft.app.notification.confirmable.entity.ConfirmableNotificationEntity;
import com.mannschaft.app.notification.confirmable.repository.ConfirmableNotificationRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

/**
 * F04.9 確認通知期限切れバッチサービス。
 *
 * <p>毎日 AM 3:00 に ACTIVE かつ deadline_at が現在日時を過ぎた通知を
 * EXPIRED ステータスに変更する。</p>
 *
 * <p>ShedLock により複数インスタンス起動時の二重実行を防ぐ。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ConfirmableNotificationExpiryBatchService {

    private final ConfirmableNotificationRepository notificationRepository;

    /**
     * 期限切れバッチを実行する。
     *
     * <p>ACTIVE かつ deadline_at が現在日時より前の通知を一括で EXPIRED に変更する。</p>
     */
    @BackgroundFeaturePolicy(mode = BackgroundFeatureMode.ALWAYS,
            reason = "対応する gate_key が無く停止条件を宣言できないため常時実行する。期限超過の確認通知を EXPIRED へ更新する処理であり、再開後に同じ条件で拾い直せる。機能単位の閉栓が要るようになった時点で gate_key の発行から検討すること")
    @BatchEndpoint(name = "notification-confirmable-expiry-daily", description = "期限超過の確認通知を毎日 03:00 に EXPIRED へ更新する")
    @Scheduled(cron = "0 0 3 * * *") // 毎日 AM 3:00
    @SchedulerLock(
            name = "confirmableNotificationExpiryBatch",
            lockAtLeastFor = "PT5M",
            lockAtMostFor = "PT10M")
    @Transactional
    public void runBatch() {
        LocalDateTime now = LocalDateTime.now();
        log.info("確認通知期限切れバッチ開始: {}", now);

        // ACTIVE かつ deadline_at が現在日時より前の通知を取得
        List<ConfirmableNotificationEntity> expiredTargets =
                notificationRepository.findExpiredNotifications(now);

        if (expiredTargets.isEmpty()) {
            log.debug("期限切れ対象の確認通知なし");
            return;
        }

        int expiredCount = 0;
        for (ConfirmableNotificationEntity notification : expiredTargets) {
            try {
                // expire() ドメインメソッドを呼び出してステータスを EXPIRED に変更
                notification.expire();
                notificationRepository.save(notification);
                expiredCount++;

                log.debug("確認通知を期限切れに変更: notificationId={}, deadlineAt={}",
                        notification.getId(), notification.getDeadlineAt());
            } catch (Exception e) {
                log.error("確認通知期限切れ処理失敗: notificationId={}, error={}",
                        notification.getId(), e.getMessage());
            }
        }

        log.info("確認通知期限切れバッチ完了: 対象={}, 期限切れ処理={}", expiredTargets.size(), expiredCount);
    }

    /**
     * CMP-260920-1040: 期限切れ対象の ID だけを抽出する（軍議第8版確定稿 §11.1 手順1）。
     *
     * <p>エンティティは読み込まない（{@code SELECT id ... WHERE status='ACTIVE' AND deadline_at < now}）。
     * 骨格のみ（試練B）。実装は出陣で行う。</p>
     *
     * @param now 現在日時
     * @return 期限切れ対象の確認通知 ID 一覧
     */
    public List<Long> findExpiredIds(LocalDateTime now) {
        throw new UnsupportedOperationException("CMP-260920-1040 出陣で実装");
    }

    /**
     * CMP-260920-1040: ID 1件ごとに独立したトランザクション（REQUIRES_NEW）で、親を
     * {@code findByIdForUpdate} でロックして最新の状態を読み、ACTIVE かつ期限を過ぎている場合だけ
     * EXPIRED にする（軍議第8版確定稿 §11.1 手順2）。
     *
     * <p>この間に別トランザクションが先に COMPLETED を確定していた場合は何もしない（AC-67）。
     * 骨格のみ（試練B）。実装は出陣で行う。</p>
     *
     * @param notificationId 確認通知 ID
     * @param now            現在日時
     * @return EXPIRED に遷移させたら true。ACTIVE でなくなっていた等で何もしなかったら false
     */
    public boolean expireOneWithLock(Long notificationId, LocalDateTime now) {
        throw new UnsupportedOperationException("CMP-260920-1040 出陣で実装");
    }
}
