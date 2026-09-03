package com.mannschaft.app.notification.credit.batch;

import com.mannschaft.app.auth.AuditEventType;
import com.mannschaft.app.auth.service.AuditLogService;
import com.mannschaft.app.notification.credit.entity.NotificationCreditPurchaseEntity;
import com.mannschaft.app.notification.credit.entity.OrganizationNotificationBalanceEntity;
import com.mannschaft.app.notification.credit.repository.NotificationCreditPurchaseRepository;
import com.mannschaft.app.notification.credit.repository.OrganizationNotificationBalanceRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;

/**
 * 通知クレジット有効期限バッチの 1 項目実行 Bean（Issue #2990 L4）。
 *
 * <p>{@code notification/credit/service/NotificationCreditResetRunner} と同形。
 * {@link NotificationCreditExpiryBatch} が非トランザクションのオーケストレータとして
 * 項目ごとに本 Bean を呼ぶ。</p>
 *
 * <h2>なぜ独立 Bean・独立トランザクションが要るのか</h2>
 * <p>是正前の {@code NotificationCreditExpiryBatch#runBatch} は<b>バッチ全体が単一の
 * {@code @Transactional}</b> であり、項目ごとの {@code try/catch} は隔離として機能していなかった。
 * 通知側の DB 例外が発生すると、catch してログを出しても<b>そのトランザクションは
 * rollback-only のまま</b>であり、コミット時に {@code UnexpectedRollbackException} となって
 * バッチ全体（アラート送信済フラグ・{@code expired_at}・<b>残高からの失効分の差し引き</b>）が
 * まとめて巻き戻っていた。1 項目 = 1 独立トランザクションにすることで、1 件の失敗が
 * 他の組織のクレジット失効処理を巻き添えにしない。</p>
 *
 * <p>{@link Propagation#REQUIRES_NEW} は同一 Bean 内の自己呼び出しではプロキシを経ず効かないため、
 * 呼び出し元と別 Bean に切り出している。</p>
 *
 * <h2>エンティティを引き継がず ID で読み直す</h2>
 * <p>オーケストレータ側の検索結果は独立トランザクションの外で得た detached なエンティティである。
 * 本 Bean は必ず ID で読み直し、読み直せなければ {@code null} を返して「対象が消えた」ことを
 * 呼び出し元へ伝える（{@code NotificationCreditResetRunner#resetOne} と同じ規約）。</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class NotificationCreditExpiryRunner {

    private final NotificationCreditPurchaseRepository purchaseRepository;
    private final OrganizationNotificationBalanceRepository balanceRepository;
    private final AuditLogService auditLogService;

    /**
     * 期限アラート送信済みフラグを立てる（1 項目 = 1 独立トランザクション）。
     *
     * @param purchaseId    購入ID
     * @param daysRemaining 残り日数（30 または 7）
     * @return アラート送信に必要な情報。対象が消えていた場合は {@code null}
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public AlertTarget markAlertSent(Long purchaseId, int daysRemaining) {
        NotificationCreditPurchaseEntity purchase = purchaseRepository.findById(purchaseId).orElse(null);
        if (purchase == null) {
            log.warn("期限アラート対象が見当たらず（スキップ）: purchaseId={}, daysRemaining={}",
                    purchaseId, daysRemaining);
            return null;
        }
        if (daysRemaining == 30) {
            purchase.markAlertSent30d();
        } else {
            purchase.markAlertSent7d();
        }
        purchaseRepository.save(purchase);
        // 有効期限は通知本文で「日付」としてしか使わない。ここで LocalDate に落としておくことで、
        // 日時ポリシー（docs/architecture/datetime_policy_utc_instant_vs_wallclock.md）が禁じる
        // 新規 LocalDateTime フィールドを増やさずに済む（番人 DateTimeAndZoneGuardTest）。
        LocalDate expiresOn = purchase.getExpiresAt() == null ? null : purchase.getExpiresAt().toLocalDate();
        return new AlertTarget(purchase.getOrganizationId(), purchase.getId(), expiresOn);
    }

    /**
     * 1 件の購入を失効させる（1 項目 = 1 独立トランザクション）。
     *
     * <p>残高からの差し引き・{@code expired_at} の設定・監査ログ記録を同一トランザクションで行う。
     * いずれかが失敗すればこの 1 件だけが巻き戻り、他の項目には波及しない。</p>
     *
     * @param purchaseId 購入ID
     * @return 失効結果。対象が消えていた場合は {@code null}
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public ExpiryOutcome expireOne(Long purchaseId) {
        NotificationCreditPurchaseEntity purchase = purchaseRepository.findById(purchaseId).orElse(null);
        if (purchase == null) {
            log.warn("失効対象が見当たらず（スキップ）: purchaseId={}", purchaseId);
            return null;
        }

        long expiredCredits = purchase.getRemainingCredits();
        if (expiredCredits <= 0) {
            // 既に消費済みの場合はフラグのみ更新（アラートは送らない）
            purchase.markExpired();
            purchaseRepository.save(purchase);
            return new ExpiryOutcome(purchase.getOrganizationId(), 0L);
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

        return new ExpiryOutcome(purchase.getOrganizationId(), expiredCredits);
    }

    /**
     * 期限アラート送信に必要な情報。
     *
     * @param organizationId 組織ID
     * @param purchaseId     購入ID
     * @param expiresOn      有効期限日（通知本文は日付単位でしか使わないため壁時計の日付で持つ）
     */
    public record AlertTarget(Long organizationId, Long purchaseId, LocalDate expiresOn) {
    }

    /**
     * 失効処理の結果。
     *
     * @param organizationId 組織ID
     * @param expiredCredits 失効した通数（0 なら消費済みでアラート不要）
     */
    public record ExpiryOutcome(Long organizationId, long expiredCredits) {
    }
}
