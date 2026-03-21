package com.mannschaft.app.payment.service;

import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.payment.PaymentErrorCode;
import com.mannschaft.app.payment.PaymentItemType;
import com.mannschaft.app.payment.PaymentStatus;
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

        switch (event.type()) {
            case "checkout.session.completed" -> handleCheckoutCompleted(event);
            case "checkout.session.expired" -> handleCheckoutExpired(event);
            case "charge.refunded" -> handleChargeRefunded(event);
            default -> log.info("未対応の Webhook イベント: type={}", event.type());
        }
    }

    /**
     * checkout.session.completed を処理する。
     */
    private void handleCheckoutCompleted(StripePaymentProvider.WebhookEventInfo event) {
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
     */
    private void handleChargeRefunded(StripePaymentProvider.WebhookEventInfo event) {
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
