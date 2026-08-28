package com.mannschaft.app.notification.credit.batch;

import com.mannschaft.app.admin.batch.BatchEndpoint;
import com.mannschaft.app.auth.AuditEventType;
import com.mannschaft.app.auth.service.AuditLogService;
import com.mannschaft.app.common.backgroundgate.BackgroundFeatureMode;
import com.mannschaft.app.common.backgroundgate.BackgroundFeaturePolicy;
import com.mannschaft.app.notification.NotificationScopeType;
import com.mannschaft.app.notification.credit.entity.NotificationCreditPurchaseEntity;
import com.mannschaft.app.notification.credit.entity.NotificationCreditPurchaseStatus;
import com.mannschaft.app.notification.credit.entity.OrganizationNotificationBalanceEntity;
import com.mannschaft.app.notification.credit.repository.NotificationCreditPurchaseRepository;
import com.mannschaft.app.notification.credit.repository.OrganizationNotificationBalanceRepository;
import com.mannschaft.app.notification.service.NotificationHelper;
import com.mannschaft.app.role.repository.UserRoleRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.springframework.context.MessageSource;
import org.springframework.scheduling.annotation.Async;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

/**
 * F09.13 通知クレジット有効期限バッチ。
 *
 * <p>毎日 AM 3:00（JST）に以下を実行する:</p>
 * <ol>
 *   <li>有効期限30日前アラート（未送信のもの）</li>
 *   <li>有効期限7日前アラート（未送信のもの）</li>
 *   <li>有効期限切れの失効処理（{@code remaining_credits} を {@code credit_balance} から差し引く）</li>
 * </ol>
 *
 * <p>ShedLock により複数インスタンス起動時の二重実行を防ぐ。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class NotificationCreditExpiryBatch {

    private final NotificationCreditPurchaseRepository purchaseRepository;
    private final OrganizationNotificationBalanceRepository balanceRepository;
    private final NotificationHelper notificationHelper;
    private final UserRoleRepository userRoleRepository;
    private final AuditLogService auditLogService;
    /**
     * Issue #2715 CMP-055 ロットC-1: 通知本文の i18n。受信者 locale の解決自体は
     * {@link NotificationHelper#notifyAllLocalized} 内部の {@link UserLocaleCache} が一括で担う
     * （本クラスから直接 {@code UserLocaleCache} は呼ばない）。
     */
    private final MessageSource messageSource;

    /**
     * 有効期限バッチを実行する（毎日 AM 3:00 JST）。
     */
    @BackgroundFeaturePolicy(mode = BackgroundFeatureMode.ALWAYS,
            reason = "止めると期限切れクレジットが credit_balance から差し引かれず、実際には使えない残高が組織に残り続ける")
    @BatchEndpoint(name = "notification-credit-expiry-daily", description = "通知クレジットの期限アラートと失効処理を毎日 03:00 に実行する")
    @Scheduled(cron = "0 0 3 * * *", zone = "Asia/Tokyo")
    @SchedulerLock(
            name = "notificationCreditExpiryBatch",
            lockAtLeastFor = "PT5M",
            lockAtMostFor = "PT20M")
    @Transactional
    public void runBatch() {
        LocalDateTime now = LocalDateTime.now();
        log.info("通知クレジット有効期限バッチ開始: {}", now);

        // ─── 30日前アラート ───
        process30DayAlert(now);

        // ─── 7日前アラート ───
        process7DayAlert(now);

        // ─── 失効処理 ───
        processExpiry(now);

        log.info("通知クレジット有効期限バッチ完了: {}", now);
    }

    // ─────────────────────────────────────────────────────────
    // プライベートメソッド
    // ─────────────────────────────────────────────────────────

    /**
     * 有効期限30日前アラートを処理する。
     */
    private void process30DayAlert(LocalDateTime now) {
        List<NotificationCreditPurchaseEntity> targets =
                purchaseRepository.findByExpiresAtBetweenAndPaymentStatusAndAlertSent30dFalse(
                        now, now.plusDays(30), NotificationCreditPurchaseStatus.PAID);

        for (NotificationCreditPurchaseEntity purchase : targets) {
            try {
                purchase.markAlertSent30d();
                purchaseRepository.save(purchase);
                sendExpiryAlertAsync(purchase.getOrganizationId(), purchase.getId(),
                        purchase.getExpiresAt(), 30);
            } catch (Exception e) {
                log.error("30日前アラート処理失敗: purchaseId={}", purchase.getId(), e);
            }
        }

        if (!targets.isEmpty()) {
            log.info("30日前アラート送信: {}件", targets.size());
        }
    }

    /**
     * 有効期限7日前アラートを処理する。
     */
    private void process7DayAlert(LocalDateTime now) {
        List<NotificationCreditPurchaseEntity> targets =
                purchaseRepository.findByExpiresAtBetweenAndPaymentStatusAndAlertSent7dFalse(
                        now, now.plusDays(7), NotificationCreditPurchaseStatus.PAID);

        for (NotificationCreditPurchaseEntity purchase : targets) {
            try {
                purchase.markAlertSent7d();
                purchaseRepository.save(purchase);
                sendExpiryAlertAsync(purchase.getOrganizationId(), purchase.getId(),
                        purchase.getExpiresAt(), 7);
            } catch (Exception e) {
                log.error("7日前アラート処理失敗: purchaseId={}", purchase.getId(), e);
            }
        }

        if (!targets.isEmpty()) {
            log.info("7日前アラート送信: {}件", targets.size());
        }
    }

    /**
     * 有効期限切れの失効処理を実施する（FIFO）。
     */
    private void processExpiry(LocalDateTime now) {
        List<NotificationCreditPurchaseEntity> expiredTargets =
                purchaseRepository.findByExpiresAtBeforeAndPaymentStatusAndExpiredAtIsNull(
                        now, NotificationCreditPurchaseStatus.PAID);

        for (NotificationCreditPurchaseEntity purchase : expiredTargets) {
            try {
                long expiredCredits = purchase.getRemainingCredits();
                if (expiredCredits <= 0) {
                    // 既に消費済みの場合はフラグのみ更新
                    purchase.markExpired();
                    purchaseRepository.save(purchase);
                    continue;
                }

                // クレジット残高から失効分を差し引く
                OrganizationNotificationBalanceEntity balance =
                        balanceRepository.findByOrganizationIdForUpdate(purchase.getOrganizationId())
                                .orElse(null);
                if (balance != null) {
                    balance.consumeCredit(expiredCredits);
                    balanceRepository.save(balance);
                }

                // 購入レコードを失効済みにする
                purchase.markExpired();
                purchaseRepository.save(purchase);

                // 監査ログ記録
                auditLogService.record(
                        AuditEventType.NOTIFICATION_CREDIT_EXPIRED.name(),
                        null, null, null,
                        purchase.getOrganizationId(),
                        null, null, null,
                        "{\"purchaseId\":" + purchase.getId()
                                + ",\"expiredCredits\":" + expiredCredits + "}"
                );

                log.info("クレジット失効処理: purchaseId={}, organizationId={}, expiredCredits={}",
                        purchase.getId(), purchase.getOrganizationId(), expiredCredits);

                // 失効アラートを送信
                sendCreditExpiredAlertAsync(purchase.getOrganizationId(), expiredCredits);

            } catch (Exception e) {
                log.error("失効処理失敗: purchaseId={}", purchase.getId(), e);
            }
        }

        if (!expiredTargets.isEmpty()) {
            log.info("クレジット失効処理完了: {}件", expiredTargets.size());
        }
    }

    /**
     * 有効期限アラートをADMINへ非同期送信する。
     *
     * @param organizationId 組織ID
     * @param purchaseId     購入ID
     * @param expiresAt      有効期限日時
     * @param daysRemaining  残り日数
     */
    @Async
    protected void sendExpiryAlertAsync(Long organizationId, Long purchaseId,
                                        LocalDateTime expiresAt, int daysRemaining) {
        try {
            List<Long> adminUserIds = userRoleRepository.findAdminUserIdsByOrganizationId(organizationId);
            if (adminUserIds.isEmpty()) {
                return;
            }
            notificationHelper.notifyAllLocalized(
                    adminUserIds,
                    "NOTIFICATION_CREDIT_EXPIRY_ALERT",
                    "NOTIFICATION_CREDIT",
                    organizationId,
                    NotificationScopeType.ORGANIZATION,
                    organizationId,
                    "/organizations/" + organizationId + "/settings/notification-credits",
                    null,
                    (userId, locale) -> new NotificationHelper.LocalizedMessage(
                            messageSource.getMessage(
                                    "notification.credit.expiryAlert.title",
                                    new Object[]{daysRemaining},
                                    "通知クレジットの有効期限まで残り" + daysRemaining + "日です", locale),
                            messageSource.getMessage(
                                    "notification.credit.expiryAlert.body",
                                    new Object[]{purchaseId, expiresAt.toLocalDate()},
                                    "購入ID#" + purchaseId + "の通知クレジットが "
                                            + expiresAt.toLocalDate() + " に失効します。期限前にご利用ください。",
                                    locale))
            );
        } catch (Exception e) {
            log.error("有効期限アラート送信失敗: organizationId={}, purchaseId={}", organizationId, purchaseId, e);
        }
    }

    /**
     * クレジット失効通知をADMINへ非同期送信する。
     *
     * @param organizationId  組織ID
     * @param expiredCredits  失効したクレジット通数
     */
    @Async
    protected void sendCreditExpiredAlertAsync(Long organizationId, long expiredCredits) {
        try {
            List<Long> adminUserIds = userRoleRepository.findAdminUserIdsByOrganizationId(organizationId);
            if (adminUserIds.isEmpty()) {
                return;
            }
            notificationHelper.notifyAllLocalized(
                    adminUserIds,
                    "NOTIFICATION_CREDIT_EXPIRED",
                    "NOTIFICATION_CREDIT",
                    organizationId,
                    NotificationScopeType.ORGANIZATION,
                    organizationId,
                    "/organizations/" + organizationId + "/settings/notification-credits",
                    null,
                    (userId, locale) -> new NotificationHelper.LocalizedMessage(
                            messageSource.getMessage(
                                    "notification.credit.expired.title", null,
                                    "通知クレジットが失効しました", locale),
                            messageSource.getMessage(
                                    "notification.credit.expired.body",
                                    new Object[]{expiredCredits},
                                    expiredCredits + "通のクレジットが有効期限切れにより失効しました。", locale))
            );
        } catch (Exception e) {
            log.error("クレジット失効アラート送信失敗: organizationId={}", organizationId, e);
        }
    }
}
