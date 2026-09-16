package com.mannschaft.app.payment.escrow;

import com.mannschaft.app.common.backgroundgate.BackgroundFeatureMode;
import com.mannschaft.app.common.backgroundgate.BackgroundFeaturePolicy;
import com.mannschaft.app.payment.escrow.event.EscrowCapturedEvent;
import com.mannschaft.app.payment.service.MemberPaymentService;
import com.mannschaft.app.payment.service.MembershipSubscriptionService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import java.util.UUID;

/**
 * F08.9 P1 Wave4 (T8): escrow の CAPTURED（capture 確定）→ 会費 {@code member_payments} の PENDING→PAID 反映を
 * 起こすリスナ（設計書 F08.9 02 §1.1 / §4.2 状態機械）。
 *
 * <p><b>なぜリスナで分けるか（ドメイン境界の維持・CLAUDE.md 原則1/5）:</b> 会費の即時 charge は Stripe の
 * {@code payment_intent.succeeded} platform Webhook で CAPTURED 確定する（{@link EscrowWebhookService} の
 * {@code applySucceeded}）。しかし escrow（F22.1 money rail）は会費の {@code member_payments}（F08.9）を知らない。
 * そこで escrow 側は {@link EscrowCapturedEvent} を発火するだけに留め、PAID 反映は会費ドメイン側の本リスナが行う。
 * これにより escrow が会費 Entity を直接参照する逆依存を作らず、{@code @Transactional} をドメイン内に閉じる。</p>
 *
 * <p><b>なぜ AFTER_COMMIT か（TX 整合）:</b> CAPTURED 確定と ledger 起票が durable になってから PAID 反映を走らせる
 * ため {@link TransactionPhase#AFTER_COMMIT} で受ける。webhook 処理 TX がロールバックすれば CAPTURED は成立せず、
 * 本リスナも起動しない（AFTER_COMMIT は commit 成功時のみ配送）。{@link MemberPaymentService#applyMembershipPaidByEscrow}
 * は会費ドメインの独立トランザクション（{@code @Transactional}）で member_payment を更新する。</p>
 *
 * <p><b>会費のみ拾う・冪等:</b> {@code sourceKind != MEMBERSHIP}（RECRUITMENT 等）は早期 return で無視する
 * （謝礼の払出には member_payments 反映は不要）。会費でも、対象 member_payment が無い／既に PAID の場合は
 * {@link MemberPaymentService#applyMembershipPaidByEscrow} 側で no-op（冪等）。webhook 再送・同期確定の二経路で
 * 二度配送されても二重 PAID にしない。</p>
 *
 * <p><b>失敗は握り潰さない:</b> 反映に失敗した場合は例外をそのままログに残して再送出する（AFTER_COMMIT ゆえ
 * webhook TX は既にコミット済みで巻き戻らないが、{@code payment_intent.succeeded} webhook の再送＋本リスナの冪等で
 * 後追い反映できる結果整合とする・症状を隠さない）。</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class MembershipPaymentCaptureListener {

    private final MemberPaymentService memberPaymentService;
    /** F08.9 P5 第三波: 初回単発 charge CAPTURED 由来の継続課金 PENDING→ACTIVE 化（案b の唯一の活性化点）。 */
    private final MembershipSubscriptionService membershipSubscriptionService;

    /**
     * escrow の CAPTURED イベントを受けて、会費（MEMBERSHIP）の member_payment を PENDING→PAID に反映する。
     *
     * <p><b>F08.9 P5 第三波（継続課金の活性化）:</b> PAID 反映の戻り値が継続課金 ID（連結 member_payment が
     * {@code membership_subscription_id} を持つ＝初回単発 charge 由来）の場合、続けて
     * {@link MembershipSubscriptionService#activateOnInitialChargeIfPending} を呼び PENDING→ACTIVE 化する。
     * これが案b における PENDING→ACTIVE の<b>唯一の発火点</b>（Webhook 側は活性化しない）。単発会費（subscription
     * 連結なし）は戻り値 null で活性化は走らない。</p>
     *
     * @param event CAPTURED イベント（escrowTransactionId / sourceKind）
     */
    @BackgroundFeaturePolicy(mode = BackgroundFeatureMode.ALWAYS,
            reason = "止めるとエスクロー確定済みの会費が実決済に反映されず、DB 上は確定・決済は未実行という乖離が残る")
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onEscrowCaptured(EscrowCapturedEvent event) {
        if (event.sourceKind() != EscrowSourceKind.MEMBERSHIP) {
            // 会費以外（謝礼の払出等）は member_payments 反映の対象外。
            return;
        }
        log.info("会費 CAPTURED → PAID 反映を起動: escrowId={}", event.escrowTransactionId());
        UUID membershipSubscriptionId = memberPaymentService.applyMembershipPaidByEscrow(event.escrowTransactionId());
        if (membershipSubscriptionId != null) {
            log.info("継続課金 初回 charge CAPTURED → PENDING→ACTIVE 化を起動: subscriptionId={}", membershipSubscriptionId);
            membershipSubscriptionService.activateOnInitialChargeIfPending(membershipSubscriptionId);
        }
    }
}
