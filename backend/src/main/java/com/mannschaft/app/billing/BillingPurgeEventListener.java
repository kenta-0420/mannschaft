package com.mannschaft.app.billing;

import com.mannschaft.app.auth.event.WithdrawalCancelledEvent;
import com.mannschaft.app.auth.event.WithdrawalRequestedEvent;
import com.mannschaft.app.gdpr.event.AccountPurgedEvent;
import com.mannschaft.app.gdpr.repository.AccountPurgeCompletionStatusRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import java.time.LocalDateTime;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

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
    private final AccountPurgeCompletionStatusRepository completionStatusRepository;

    /**
     * 退会確定（purge）: USER スコープ契約の全解約＋有償分の Stripe サブスク即時解約（AC-45）。
     *
     * <p><b>残債1（gdpr purge 完了トラッキング登録）:</b> DB 全解約＋Stripe 全件解約が両方成功した場合のみ
     * {@code account_purge_completion_status} を SUCCESS に更新する（payment ドメイン前例と同じ責務分担）。
     * 1 件でも Stripe 解約に失敗した場合は PENDING のまま残し、{@code GdprPurgeAuditBatchService} の
     * 2 時間アラート検出→{@code GdprPurgeRetryService} 経由の管理者手動 retry（{@link #retryPurge}）で
     * 拾えるようにする（従来は billing が未登録だったため、この再試行導線が存在しなかった）。</p>
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
            return; // completion status は PENDING のまま（アラート検出→手動 retry 対象）。
        }

        // Stripe 即時解約（tx 外・外部 HTTP）。失敗は契約ごとに ERROR で上申し続行（手動照合）。
        boolean stripeAllSucceeded = true;
        for (String subscriptionRef : paidSubscriptionRefs) {
            try {
                billingPaymentGateway.cancelImmediately(subscriptionRef);
            } catch (RuntimeException e) {
                stripeAllSucceeded = false;
                log.error("ユーザー退会 billing purge: Stripe サブスク即時解約失敗（課金継続の恐れ・手動照合要）"
                        + " userId={}, subscriptionRef={}", userId, subscriptionRef, e);
            }
        }
        log.info("ユーザー退会 billing purge 完了: userId={}, paidSubscriptions={}, stripeAllSucceeded={}",
                userId, paidSubscriptionRefs.size(), stripeAllSucceeded);

        if (stripeAllSucceeded) {
            completionStatusRepository.markSuccess(userId, "billing", LocalDateTime.now());
        }
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

    /**
     * 管理者からの手動 retry 用（残債1）。{@link #onAccountPurged} と同じドメイン操作を再実行するが、
     * {@code completionStatusRepository} の SUCCESS 更新は {@code GdprPurgeRetryService} が担う
     * （payment ドメイン前例 {@code PaymentPurgeEventListener#retryPurge} と同じ責務分担）。
     *
     * <p><b>Stripe リトライの穴埋め:</b> {@link BillingContractService#cancelAllUserContractsForPurge} は
     * DB 遷移が既に完了した（status=CANCELLED になった）契約を対象外にする冪等設計のため、単純にこのメソッド
     * だけを再呼び出ししても「DB は解約済みだが前回 Stripe 解約が失敗した」契約の subscriptionRef を拾えない。
     * {@link BillingContractService#findPurgedPaidSubscriptionRefsPendingStripeCancel} で拾い、
     * DB 遷移分の subscriptionRef と合算（重複除去）して Stripe 解約を retry する。
     * {@code StripeBillingPaymentGateway#cancelImmediately} 実装側で Stripe の「既に解約済み」状態を
     * 冪等スキップするため、同じ subscriptionRef を複数回 retry しても二重解約にはならない。</p>
     *
     * <p><b>トランザクション方針:</b> {@link #onAccountPurged} と異なり本メソッドは
     * {@code @Transactional} を付与する。呼び出し元 {@code GdprPurgeRetryService#retryDomainPurge} が
     * 既に {@code @Transactional} で本メソッド呼び出しを含む一連の処理を包んでいる（既存 6 ドメイン共通の
     * 管理者手動 retry 導線）ため、本メソッド単独を non-transactional にしても Stripe 呼び出しが
     * 外側のトランザクションから抜け出せるわけではない。管理者操作は低頻度（自動 purge の AFTER_COMMIT
     * 経路とは異なりホットパスではない）ため、この既存導線と同じ制約を許容する。</p>
     *
     * @param userId retry 対象ユーザー ID
     * @return true=全操作成功、false=1 件以上失敗
     */
    @Transactional
    public boolean retryPurge(Long userId) {
        boolean dbCancelFailed = false;
        Set<String> subscriptionRefs = new LinkedHashSet<>();
        try {
            subscriptionRefs.addAll(billingContractService.cancelAllUserContractsForPurge(userId));
        } catch (Exception e) {
            dbCancelFailed = true;
            log.error("billing purge retry: 契約解約失敗 userId={}", userId, e);
        }
        try {
            subscriptionRefs.addAll(
                    billingContractService.findPurgedPaidSubscriptionRefsPendingStripeCancel(userId));
        } catch (Exception e) {
            log.warn("billing purge retry: Stripe 未確認解約契約の取得失敗 userId={}", userId, e);
        }

        boolean stripeAllSucceeded = true;
        for (String subscriptionRef : subscriptionRefs) {
            try {
                billingPaymentGateway.cancelImmediately(subscriptionRef);
            } catch (RuntimeException e) {
                stripeAllSucceeded = false;
                log.error("billing purge retry: Stripe サブスク即時解約失敗 userId={}, subscriptionRef={}",
                        userId, subscriptionRef, e);
            }
        }
        return !dbCancelFailed && stripeAllSucceeded;
    }
}
