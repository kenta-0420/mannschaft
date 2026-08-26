package com.mannschaft.app.payment.service;

import com.mannschaft.app.billing.BillingSubscriptionWebhookService;
import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.notification.credit.service.NotificationCreditCheckoutService;
import com.mannschaft.app.payment.PaymentErrorCode;
import com.mannschaft.app.payment.PaymentStatus;
import com.mannschaft.app.payment.escrow.EscrowWebhookService;
import com.mannschaft.app.payment.entity.MemberPaymentEntity;
import com.mannschaft.app.payment.entity.PaymentItemEntity;
import com.mannschaft.app.payment.repository.MemberPaymentRepository;
import com.mannschaft.app.payment.stripe.StripePaymentProvider;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;

/**
 * Stripe Webhook 受信サービス。Webhook イベントの処理を担当する。
 */
@Slf4j
@Service
@RequiredArgsConstructor
@Transactional
public class StripeWebhookService {

    private final MemberPaymentRepository memberPaymentRepository;
    private final PaymentItemService paymentItemService;
    private final StripePaymentProvider stripePaymentProvider;
    // TODO: notificationドメイン → paymentドメインの依存。将来はWebhookEventで分離予定
    private final NotificationCreditCheckoutService notificationCreditCheckoutService;
    /** F22.1 統一決済 P2-b: 与信系（escrow）PaymentIntent イベントの委譲先（設計書 02 §4.2）。 */
    private final EscrowWebhookService escrowWebhookService;
    /** F08.9 P5 第三波: 継続課金（invoice.* / subscription.deleted）イベントの委譲先（設計書 02 §4.2）。 */
    private final MembershipSubscriptionWebhookService membershipSubscriptionWebhookService;
    // TODO: billing ドメイン → payment ドメインの委譲（NotificationCreditCheckoutService と同型）。将来は WebhookEvent で分離予定
    /** F20.1 実決済: 自社受取サブスク（checkout.session.* / invoice.* / subscription.deleted）の委譲先（D-2 で F08.9 と分離）。 */
    private final BillingSubscriptionWebhookService billingSubscriptionWebhookService;

    /** F22.1 与信系 platform Webhook の対象イベント種別（payment_intent.* の接頭辞）。 */
    private static final String ESCROW_EVENT_PREFIX = "payment_intent.";

    /**
     * Stripe Webhook を処理する。
     *
     * @param payload   生リクエストボディ
     * @param sigHeader Stripe-Signature ヘッダー
     */
    public void handleWebhook(String payload, String sigHeader) {
        StripePaymentProvider.WebhookEventInfo event;
        try {
            event = stripePaymentProvider.constructEvent(payload, sigHeader);
        } catch (Exception e) {
            throw new BusinessException(PaymentErrorCode.WEBHOOK_SIGNATURE_INVALID, e);
        }

        // F22.1: 与信系（Destination Charge の PaymentIntent）は platform 上に作られ platform Webhook で届く。
        // event_id 冪等＋escrow 特定は EscrowWebhookService に委譲する（設計書 02 §4.2・専用 record で再パース）。
        if (event.type() != null && event.type().startsWith(ESCROW_EVENT_PREFIX)) {
            escrowWebhookService.handleWebhook(payload, sigHeader);
            return;
        }

        // F08.9 P5: 継続課金（Subscription）は platform 上に作られ invoice.* / customer.subscription.deleted が
        // platform Webhook で届く。event_id 冪等＋subscription 逆引き＋invoice.created 固定手数料上書きは
        // MembershipSubscriptionWebhookService に委譲する（設計書 02 §4.2・専用 record で再パース）。
        if (MembershipSubscriptionWebhookService.isSubscriptionEvent(event.type())) {
            // F20.1: 自社受取サブスク（billing）は subscriptionId を psp_subscription_ref で逆引きしてヒットすれば
            // billing が処理する。無関係なら false → 従来どおり F08.9 会費側へ（D-2・相互 no-op・AC-38）。
            if (billingSubscriptionWebhookService.handleSubscriptionEventIfBilling(payload, sigHeader)) {
                return;
            }
            membershipSubscriptionWebhookService.handleWebhook(payload, sigHeader);
            return;
        }

        switch (event.type()) {
            case "checkout.session.completed" -> {
                // F20.1: metadata.billingContractId があれば billing（サブスク契約 PENDING→ACTIVE）が処理。
                // 無ければ従来の会員費/通知クレジット処理へ。
                if (!billingSubscriptionWebhookService.handleCheckoutCompletedIfBilling(payload, sigHeader)) {
                    handleCheckoutCompleted(event);
                }
            }
            case "checkout.session.expired" -> {
                if (!billingSubscriptionWebhookService.handleCheckoutExpiredIfBilling(payload, sigHeader)) {
                    handleCheckoutExpired(event);
                }
            }
            case "charge.refunded" -> handleChargeRefunded(event, payload, sigHeader);
            default -> log.info("未対応の Webhook イベント: type={}", event.type());
        }

    }

    /**
     * checkout.session.completed を処理する。
     *
     * <p>F09.13: {@code notificationCreditPurchaseId} が含まれる場合は通知クレジット購入として
     * {@link NotificationCreditCheckoutService#handlePurchaseCompleted} に委譲する。
     * それ以外は通常の会員費支払いとして処理する。</p>
     */
    private void handleCheckoutCompleted(StripePaymentProvider.WebhookEventInfo event) {
        // F09.13: 通知クレジット購入の場合は専用サービスへ委譲
        if (event.notificationCreditPurchaseId() != null) {
            notificationCreditCheckoutService.handlePurchaseCompleted(event);
            return;
        }

        if (event.memberPaymentId() == null) {
            log.warn("memberPaymentId が metadata に含まれていません");
            return;
        }

        Long memberPaymentId = Long.parseLong(event.memberPaymentId());
        MemberPaymentEntity payment = memberPaymentRepository.findById(memberPaymentId).orElse(null);
        if (payment == null) {
            log.warn("支払い記録が見つかりません: memberPaymentId={}", memberPaymentId);
            return;
        }

        // 冪等処理: PAID 済みはスキップ
        if (payment.getStatus() == PaymentStatus.PAID) {
            log.info("既に PAID 済み。スキップします: memberPaymentId={}", memberPaymentId);
            return;
        }

        PaymentItemEntity paymentItem = paymentItemService.findByIdOrThrow(payment.getPaymentItemId());

        LocalDate validFrom = LocalDate.now();
        LocalDate validUntil = switch (paymentItem.getType()) {
            case ANNUAL_FEE -> validFrom.plusDays(365);
            case MONTHLY_FEE -> validFrom.plusDays(31);
            case ITEM, DONATION -> null;
            // F08.9 P6: TERM 型は paymentItem.termEndsOn を有効期限とする
            case TERM -> paymentItem.getTermEndsOn();
        };

        payment.markAsPaid(
                event.paymentIntentId(),
                event.amountReceived() != null ? event.amountReceived() : payment.getAmountPaid(),
                validFrom,
                validUntil,
                event.receiptUrl()
        );
        memberPaymentRepository.save(payment);

        log.info("Checkout 完了: memberPaymentId={}, paymentIntentId={}",
                memberPaymentId, event.paymentIntentId());
    }

    /**
     * checkout.session.expired を処理する。
     */
    private void handleCheckoutExpired(StripePaymentProvider.WebhookEventInfo event) {
        if (event.memberPaymentId() == null) {
            log.warn("memberPaymentId が metadata に含まれていません");
            return;
        }

        Long memberPaymentId = Long.parseLong(event.memberPaymentId());
        MemberPaymentEntity payment = memberPaymentRepository.findById(memberPaymentId).orElse(null);
        if (payment == null) {
            log.warn("支払い記録が見つかりません: memberPaymentId={}", memberPaymentId);
            return;
        }

        // 冪等処理: PAID / CANCELLED 済みはスキップ
        if (payment.getStatus() != PaymentStatus.PENDING) {
            log.info("PENDING 以外の状態。スキップします: memberPaymentId={}, status={}",
                    memberPaymentId, payment.getStatus());
            return;
        }

        payment.markAsCancelled();
        memberPaymentRepository.save(payment);

        log.info("Checkout 期限切れ: memberPaymentId={}", memberPaymentId);
    }

    /**
     * charge.refunded を処理する。
     *
     * <p>F22.1 P2-c 第二波: 謝礼/会費（escrow・Connect）の返金もこの event で届く。まず escrow 側
     * （{@link EscrowWebhookService#handleChargeRefunded}・event_id 冪等＋行ロック）へ委譲を試み、対象 escrow が
     * 無ければ {@code false} が返るので既存会員費（{@link MemberPaymentEntity}）の返金処理へフォールバックする
     * （設計書 02 §6.1）。</p>
     */
    private void handleChargeRefunded(StripePaymentProvider.WebhookEventInfo event, String payload, String sigHeader) {
        // F22.1: escrow（Connect）返金を優先委譲。対象 escrow があれば escrow 側が処理し true を返す。
        if (escrowWebhookService.handleChargeRefunded(payload, sigHeader)) {
            return;
        }

        if (event.paymentIntentId() == null) {
            log.warn("paymentIntentId が含まれていません");
            return;
        }

        MemberPaymentEntity payment = memberPaymentRepository
                .findByStripePaymentIntentId(event.paymentIntentId())
                .orElse(null);
        if (payment == null) {
            log.warn("支払い記録が見つかりません: paymentIntentId={}", event.paymentIntentId());
            return;
        }

        // 冪等処理: REFUNDED 済みはスキップ
        if (payment.getStatus() == PaymentStatus.REFUNDED) {
            log.info("既に REFUNDED 済み。スキップします: paymentIntentId={}", event.paymentIntentId());
            return;
        }

        // 全額返金か部分返金かを判定
        if (event.refundAmount() != null && event.paymentIntentAmount() != null
                && event.refundAmount().compareTo(event.paymentIntentAmount()) < 0) {
            // 部分返金: status は PAID のまま維持
            log.info("部分返金検知: paymentIntentId={}, refundAmount={}, totalAmount={}",
                    event.paymentIntentId(), event.refundAmount(), event.paymentIntentAmount());
            return;
        }

        // 全額返金
        payment.markAsRefunded(event.refundId());
        memberPaymentRepository.save(payment);

        log.info("全額返金 Webhook 処理完了: paymentIntentId={}, refundId={}",
                event.paymentIntentId(), event.refundId());
    }
}
