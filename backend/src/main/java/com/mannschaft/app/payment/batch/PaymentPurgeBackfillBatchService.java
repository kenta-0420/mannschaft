package com.mannschaft.app.payment.batch;

import com.mannschaft.app.common.backgroundgate.BackgroundFeatureMode;
import com.mannschaft.app.common.backgroundgate.BackgroundFeaturePolicy;
import com.mannschaft.app.admin.batch.BatchEndpoint;
import com.mannschaft.app.auth.UserConstants;
import com.mannschaft.app.payment.entity.StripeCustomerEntity;
import com.mannschaft.app.payment.repository.MemberPaymentRepository;
import com.mannschaft.app.payment.repository.StripeCustomerRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * AccountPurgedEvent 処理漏れの payment ドメイン孤児を毎日 03:00 に補正する夜次バッチ。
 *
 * <p>補正対象:</p>
 * <ol>
 *   <li>{@code member_payments.user_id} が {@link UserConstants#SENTINEL_USER_ID} 以外かつ
 *       {@code users} テーブルに存在しない行 → SENTINEL_USER_ID に置換（センチネル化）</li>
 *   <li>{@code stripe_customers.user_id} が {@code users} テーブルに存在しない行 → 物理削除</li>
 * </ol>
 *
 * <p><b>なぜ必要か:</b>
 * {@link com.mannschaft.app.payment.event.PaymentPurgeEventListener} は
 * {@code AccountPurgedEvent} を受けて両操作を実行するが、非同期イベント処理のため
 * 一時的なDB障害・GC停止・アプリ再起動などによって処理が漏れる可能性がある。
 * 本バッチは「必ず 30 日以内に GDPR Art.17 が遵守されること」を保証する最終安全網として機能する。</p>
 *
 * <p><b>冪等性:</b>
 * {@code anonymizeOrphanUserId} は {@code WHERE user_id != :sentinel AND user_id NOT IN users} で
 * 絞り込むため、孤児が存在しない場合は 0 件更新で安全に終了する。
 * {@code findOrphanStripeCustomers} も同様に孤児が存在しない場合は空リストを返す。</p>
 *
 * <p>設計根拠: {@code docs/architecture/account_purge_cross_domain_refactor.md}
 * §4 Phase D-4 payment 孤児補正バッチ</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PaymentPurgeBackfillBatchService {

    private final MemberPaymentRepository memberPaymentRepository;
    private final StripeCustomerRepository stripeCustomerRepository;

    /**
     * AccountPurgedEvent 処理漏れの member_payments / stripe_customers を毎日 03:00 に補正する。
     *
     * <p>stripe_customers の削除は行ごとに独立した try-catch で囲み、
     * 1 件の削除失敗が残りの処理に影響しないよう継続実行する。
     * member_payments のバルク UPDATE が失敗した場合は例外を再スローせず WARN ログを残し、
     * 次回実行での再補正に委ねる。</p>
     */
    @BackgroundFeaturePolicy(mode = BackgroundFeatureMode.ALWAYS,
            reason = "止めると論理削除済み決済関連行の物理削除 backfill が進まず、消したはずの決済個人データが残り続ける")
    @BatchEndpoint(
            name = "payment-purge-backfill-daily",
            description = "AccountPurgedEvent 処理漏れの member_payments / stripe_customers を毎日 03:00 に補正する"
    )
    @Scheduled(cron = "0 0 3 * * *", zone = "Asia/Tokyo")
    @SchedulerLock(name = "paymentPurgeBackfillBatch", lockAtMostFor = "PT30M", lockAtLeastFor = "PT1M")
    @Transactional
    public void backfill() {
        // 操作 1: member_payments の孤児 user_id を SENTINEL_USER_ID に置換
        int anonymizedPayments = 0;
        try {
            anonymizedPayments = memberPaymentRepository.anonymizeOrphanUserId(UserConstants.SENTINEL_USER_ID);
            log.info("member_payments 孤児補正: {}件", anonymizedPayments);
        } catch (Exception e) {
            log.warn("member_payments 孤児補正失敗（次回バッチで再試行）: error={}", e.getMessage(), e);
        }

        // 操作 2: stripe_customers の孤児行を物理削除（1 件ずつ独立処理）
        List<StripeCustomerEntity> orphanStripeCustomers;
        try {
            orphanStripeCustomers = stripeCustomerRepository.findOrphanStripeCustomers();
        } catch (Exception e) {
            log.warn("stripe_customers 孤児検索失敗（次回バッチで再試行）: error={}", e.getMessage(), e);
            return;
        }

        int deletedCount = 0;
        int failedCount = 0;
        for (StripeCustomerEntity sc : orphanStripeCustomers) {
            try {
                stripeCustomerRepository.delete(sc);
                deletedCount++;
            } catch (Exception e) {
                failedCount++;
                log.error("stripe_customers 孤児補正失敗: id={}, userId={}, error={}",
                        sc.getId(), sc.getUserId(), e.getMessage(), e);
            }
        }
        log.info("stripe_customers 孤児補正: 削除{}件, 失敗{}件", deletedCount, failedCount);
    }
}
