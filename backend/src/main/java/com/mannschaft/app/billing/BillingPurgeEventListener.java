package com.mannschaft.app.billing;

import com.mannschaft.app.auth.event.WithdrawalCancelledEvent;
import com.mannschaft.app.auth.event.WithdrawalRequestedEvent;
import com.mannschaft.app.gdpr.event.AccountPurgedEvent;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import java.util.List;

/**
 * F20.1 実決済: 退会イベント連動リスナー（AC-45・設計書 03 §8・検分差し戻し2番）。
 *
 * <p><b>退会猶予との整合（01 §10 M-5・revoke は終端で復活不可）:</b></p>
 * <ul>
 *   <li><b>申請（{@link WithdrawalRequestedEvent}・猶予開始）</b>: <b>明示 no-op</b>。撤回で復活できないため
 *       契約・entitlements は revoke しない（権利維持）。</li>
 *   <li><b>撤回（{@link WithdrawalCancelledEvent}）</b>: <b>明示 no-op</b>（権利維持のまま）。</li>
 *   <li><b>確定（{@link AccountPurgedEvent}・30 日後物理削除）</b>: USER スコープの PENDING/ACTIVE/PAST_DUE
 *       契約を CANCELLED＋pointer 物理 DELETE＋由来 entitlements revoke＋evict（独立 tx）。さらに有償契約は
 *       <b>Stripe サブスクを即時解約</b>（{@code cancel_at_period_end} ではない・退会後の課金継続事故防止）。</li>
 * </ul>
 *
 * <p><b>トランザクション構成（検分4番と同思想）:</b> リスナー自身は {@code @Transactional} を付けず、
 * DB 遷移は {@link BillingContractService#cancelAllUserContractsForPurge}（{@code REQUIRES_NEW}・
 * memory {@code feedback_transactional_event_listener_requires_new} の掟）に委ね、<b>Stripe 呼び出しは
 * その tx の外</b>で行う。順序は「DB 確定 → Stripe 即時解約」: Stripe 失敗時も GDPR 上必須の権利失効は
 * 完了しており、entitlements 革除済み＋契約 CANCELLED のため webhook（invoice.paid）が届いても権利は
 * 復活しない（{@code extendContractPeriod} は PAST_DUE→ACTIVE 回復のみ）。Stripe 側の課金継続だけが残る
 * ため ERROR ログで手動照合に上申する（症状を隠さない）。</p>
 *
 * <p>例外はイベント基盤へ伝播させない（他ドメインの purge リスナーを妨げない・
 * {@code ChartPurgeEventListener} 前例）。</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class BillingPurgeEventListener {

    private final BillingContractService billingContractService;
    private final BillingPaymentGateway billingPaymentGateway;

    /**
     * 退会確定（purge）: USER スコープ契約の全解約＋有償分の Stripe サブスク即時解約（AC-45）。
     */
    @Async("purge-pool")
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onAccountPurged(AccountPurgedEvent event) {
        Long userId = event.getUserId();
        List<String> paidSubscriptionRefs;
        try {
            // DB 遷移（REQUIRES_NEW の独立 tx）: CANCELLED＋pointer DELETE＋revoke＋evict。
            paidSubscriptionRefs = billingContractService.cancelAllUserContractsForPurge(userId);
        } catch (Exception e) {
            log.error("ユーザー退会 billing purge: 契約解約失敗（手動確認要）userId={}", userId, e);
            return;
        }

        // Stripe 即時解約（tx 外・外部 HTTP）。失敗は契約ごとに ERROR で上申し続行（手動照合）。
        for (String subscriptionRef : paidSubscriptionRefs) {
            try {
                billingPaymentGateway.cancelImmediately(subscriptionRef);
            } catch (RuntimeException e) {
                log.error("ユーザー退会 billing purge: Stripe サブスク即時解約失敗（課金継続の恐れ・手動照合要）"
                        + " userId={}, subscriptionRef={}", userId, subscriptionRef, e);
            }
        }
        log.info("ユーザー退会 billing purge 完了: userId={}, paidSubscriptions={}",
                userId, paidSubscriptionRefs.size());
    }

    /**
     * 退会申請（猶予開始）: <b>明示 no-op</b>（AC-45・01 §10 M-5）。
     *
     * <p>revoke は終端操作で撤回時に復活できないため、猶予中は契約・権利を維持する。
     * 「何もしない」ことを 1 メソッドで表明し、将来「猶予中は機能を一時抑止する」等の拡張フック点を残す。</p>
     */
    @EventListener
    public void onWithdrawalRequested(WithdrawalRequestedEvent event) {
        log.debug("billing: 退会申請は no-op（猶予中は契約・権利を維持）userId={}", event.getUserId());
    }

    /** 退会撤回: <b>明示 no-op</b>（権利維持のまま・AC-45・01 §10 M-5）。 */
    @EventListener
    public void onWithdrawalCancelled(WithdrawalCancelledEvent event) {
        log.debug("billing: 退会撤回は no-op（権利維持のまま）");
    }
}
