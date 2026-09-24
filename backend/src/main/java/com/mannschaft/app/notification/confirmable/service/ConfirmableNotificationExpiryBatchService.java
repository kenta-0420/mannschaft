package com.mannschaft.app.notification.confirmable.service;

import com.mannschaft.app.admin.batch.BatchEndpoint;
import com.mannschaft.app.common.backgroundgate.BackgroundFeatureMode;
import com.mannschaft.app.common.backgroundgate.BackgroundFeaturePolicy;
import com.mannschaft.app.notification.confirmable.entity.ConfirmableNotificationEntity;
import com.mannschaft.app.notification.confirmable.entity.ConfirmableNotificationStatus;
import com.mannschaft.app.notification.confirmable.repository.ConfirmableNotificationRepository;
import lombok.extern.slf4j.Slf4j;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

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
public class ConfirmableNotificationExpiryBatchService {

    private final ConfirmableNotificationRepository notificationRepository;
    /**
     * CMP-260920-1040（軍議第8版確定稿 §11.1）: ID 1件ごとに独立したトランザクション（{@code REQUIRES_NEW}）で
     * ロック・再判定するためのテンプレート。{@code @Transactional(REQUIRES_NEW)} を自己呼び出しすると
     * Spring AOP プロキシを経由せず伝播しないため、{@link TransactionTemplate} を明示的に使う
     * （{@code NotificationFanoutJobService.enqueueTxTemplate} と同じ手法）。
     */
    private final TransactionTemplate expireOneTxTemplate;

    public ConfirmableNotificationExpiryBatchService(
            ConfirmableNotificationRepository notificationRepository,
            PlatformTransactionManager transactionManager) {
        this.notificationRepository = notificationRepository;
        this.expireOneTxTemplate = new TransactionTemplate(transactionManager);
        this.expireOneTxTemplate.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    /**
     * 期限切れバッチを実行する。
     *
     * <p>CMP-260920-1040（軍議第8版確定稿 §11.1）: ID だけを抽出し（{@link #findExpiredIds}）、
     * 1件ごとに独立したトランザクションで {@link #expireOneWithLock} を呼ぶ。1件の失敗が他の ID の
     * 処理を止めない（AC-68。失敗は握り潰さずログと件数に残す）。</p>
     */
    @BackgroundFeaturePolicy(mode = BackgroundFeatureMode.ALWAYS,
            reason = "対応する gate_key が無く停止条件を宣言できないため常時実行する。期限超過の確認通知を EXPIRED へ更新する処理であり、再開後に同じ条件で拾い直せる。機能単位の閉栓が要るようになった時点で gate_key の発行から検討すること")
    @BatchEndpoint(name = "notification-confirmable-expiry-daily", description = "期限超過の確認通知を毎日 03:00 に EXPIRED へ更新する")
    @Scheduled(cron = "0 0 3 * * *") // 毎日 AM 3:00
    @SchedulerLock(
            name = "confirmableNotificationExpiryBatch",
            lockAtLeastFor = "PT5M",
            lockAtMostFor = "PT10M")
    public void runBatch() {
        LocalDateTime now = LocalDateTime.now();
        log.info("確認通知期限切れバッチ開始: {}", now);

        List<Long> expiredTargetIds = findExpiredIds(now);

        if (expiredTargetIds.isEmpty()) {
            log.debug("期限切れ対象の確認通知なし");
            return;
        }

        int expiredCount = 0;
        for (Long notificationId : expiredTargetIds) {
            try {
                boolean expired = expireOneWithLock(notificationId, now);
                if (expired) {
                    expiredCount++;
                    log.debug("確認通知を期限切れに変更: notificationId={}", notificationId);
                }
            } catch (Exception e) {
                // 1件の失敗で他の ID の処理を止めない（AC-68）。握り潰さずログに残す。
                log.error("確認通知期限切れ処理失敗: notificationId={}, error={}",
                        notificationId, e.getMessage(), e);
            }
        }

        log.info("確認通知期限切れバッチ完了: 対象={}, 期限切れ処理={}", expiredTargetIds.size(), expiredCount);
    }

    /**
     * CMP-260920-1040: 期限切れ対象の ID だけを抽出する（軍議第8版確定稿 §11.1 手順1）。
     *
     * <p>エンティティは読み込まない（{@code SELECT id ... WHERE status='ACTIVE' AND deadline_at < now}）。</p>
     *
     * @param now 現在日時
     * @return 期限切れ対象の確認通知 ID 一覧
     */
    public List<Long> findExpiredIds(LocalDateTime now) {
        return notificationRepository.findExpiredIds(now);
    }

    /**
     * CMP-260920-1040: ID 1件ごとに独立したトランザクション（REQUIRES_NEW）で、親を
     * {@code findByIdForUpdate} でロックして最新の状態を読み、ACTIVE かつ期限を過ぎている場合だけ
     * EXPIRED にする（軍議第8版確定稿 §11.1 手順2）。
     *
     * <p>この間に別トランザクションが先に COMPLETED を確定していた場合は何もしない（AC-67）。</p>
     *
     * @param notificationId 確認通知 ID
     * @param now            現在日時
     * @return EXPIRED に遷移させたら true。ACTIVE でなくなっていた等で何もしなかったら false
     */
    public boolean expireOneWithLock(Long notificationId, LocalDateTime now) {
        return Boolean.TRUE.equals(expireOneTxTemplate.execute(status -> {
            ConfirmableNotificationEntity notification = notificationRepository.findByIdForUpdate(notificationId)
                    .orElse(null);
            if (notification == null) {
                return false;
            }
            // ロック取得後に再判定する。ロック待ちの間に別トランザクションが先に COMPLETED / CANCELLED /
            // EXPIRED を確定していた場合、ACTIVE でなくなっているためここで何もしない（AC-67）。
            if (notification.getStatus() != ConfirmableNotificationStatus.ACTIVE) {
                return false;
            }
            if (notification.getDeadlineAt() == null || !notification.getDeadlineAt().isBefore(now)) {
                return false;
            }
            notification.expire();
            notificationRepository.save(notification);
            return true;
        }));
    }
}
