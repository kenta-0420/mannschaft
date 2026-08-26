package com.mannschaft.app.billing;

import com.mannschaft.app.payment.WebhookIdempotencyService;
import com.mannschaft.app.payment.WebhookProcessStatus;
import com.mannschaft.app.payment.stripe.StripePaymentProvider;
import com.mannschaft.app.payment.stripe.StripePaymentProvider.BillingSubscriptionWebhookEventInfo;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.UUID;

/**
 * F20.1 実決済（D-1〜D-4・2026-07-10 御裁可）: 自社受取サブスクの platform Webhook 受信サービス。
 *
 * <p>{@link com.mannschaft.app.payment.service.StripeWebhookService} から委譲され、billing 所有イベントのみを
 * 処理する。所有判定は「{@code checkout.session.*}＝{@code metadata.billingContractId} の有無」「{@code invoice.*} /
 * {@code customer.subscription.deleted}＝{@code psp_subscription_ref} 逆引きヒットの有無」で行い、billing の
 * subscription でなければ何もせず {@code false} を返す（F08.9 会費 webhook へフォールバック・D-2）。</p>
 *
 * <p><b>冪等の二層</b>: (1) {@link WebhookIdempotencyService}（event_id ゲート・at-least-once 再送耐性）＋
 * (2) 各状態遷移メソッドの status 済みチェック（二重発行ゼロ・AC-34）。dispatch 失敗は握り潰さず FAILED 記録＋再送出。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class BillingSubscriptionWebhookService {

    private static final String CHECKOUT_COMPLETED = "checkout.session.completed";
    private static final String CHECKOUT_EXPIRED = "checkout.session.expired";
    private static final String INVOICE_PAID = "invoice.paid";
    private static final String INVOICE_PAYMENT_FAILED = "invoice.payment_failed";
    private static final String SUBSCRIPTION_DELETED = "customer.subscription.deleted";

    private final StripePaymentProvider stripePaymentProvider;
    private final WebhookIdempotencyService idempotencyService;
    private final BillingContractService billingContractService;
    private final BillingContractRepository billingContractRepository;
    private final Clock clock;

    /**
     * {@code checkout.session.completed} を処理する（billing 所有＝metadata に billingContractId あり）。
     *
     * @return billing が処理したら {@code true}（呼び出し側はフォールバックしない）。billing 非所有なら {@code false}。
     */
    public boolean handleCheckoutCompletedIfBilling(String payload, String sigHeader) {
        BillingSubscriptionWebhookEventInfo event = stripePaymentProvider.constructBillingSubscriptionEvent(payload, sigHeader);
        if (!CHECKOUT_COMPLETED.equals(event.type()) || event.billingContractId() == null) {
            return false;
        }
        return runGated(event, () -> {
            billingContractService.activatePaidContract(
                    UUID.fromString(event.billingContractId()), event.customerId(), event.subscriptionId(),
                    toLdt(event.currentPeriodEndEpochSec()));
            return WebhookProcessStatus.PROCESSED;
        });
    }

    /**
     * {@code checkout.session.expired} を処理する（billing 所有＝metadata に billingContractId あり）。
     */
    public boolean handleCheckoutExpiredIfBilling(String payload, String sigHeader) {
        BillingSubscriptionWebhookEventInfo event = stripePaymentProvider.constructBillingSubscriptionEvent(payload, sigHeader);
        if (!CHECKOUT_EXPIRED.equals(event.type()) || event.billingContractId() == null) {
            return false;
        }
        return runGated(event, () -> {
            billingContractService.abandonPendingContract(UUID.fromString(event.billingContractId()));
            return WebhookProcessStatus.PROCESSED;
        });
    }

    /**
     * {@code invoice.*} / {@code customer.subscription.deleted} を処理する（billing 所有＝psp_subscription_ref 逆引きヒット）。
     *
     * @return billing の subscription なら処理して {@code true}。無関係（F08.9 会費 等）なら {@code false}。
     */
    public boolean handleSubscriptionEventIfBilling(String payload, String sigHeader) {
        BillingSubscriptionWebhookEventInfo event = stripePaymentProvider.constructBillingSubscriptionEvent(payload, sigHeader);
        String subscriptionId = event.subscriptionId();
        if (subscriptionId == null
                || billingContractRepository.findByPspSubscriptionRefAndDeletedAtIsNull(subscriptionId).isEmpty()) {
            // billing の subscription ではない → F08.9 会費側へフォールバック（相互 no-op・AC-38）。
            return false;
        }
        return runGated(event, () -> switch (event.type()) {
            case INVOICE_PAID -> {
                billingContractService.extendContractPeriod(subscriptionId, toLdt(event.currentPeriodEndEpochSec()));
                yield WebhookProcessStatus.PROCESSED;
            }
            case INVOICE_PAYMENT_FAILED -> {
                billingContractService.markContractPastDue(subscriptionId);
                yield WebhookProcessStatus.PROCESSED;
            }
            case SUBSCRIPTION_DELETED -> {
                billingContractService.expireSubscriptionContract(subscriptionId, toLdt(event.currentPeriodEndEpochSec()));
                yield WebhookProcessStatus.PROCESSED;
            }
            default -> {
                log.info("F20.1 決済: 未対応の billing subscription イベント: type={}", event.type());
                yield WebhookProcessStatus.IGNORED;
            }
        });
    }

    /**
     * event_id 冪等ゲートを通してハンドラを実行する（再送耐性・FAILED 記録・再送出）。所有済みイベントの
     * 二重受信（確定済み）は処理せず {@code true} を返す（membership へフォールバックさせない）。
     */
    private boolean runGated(BillingSubscriptionWebhookEventInfo event, java.util.function.Supplier<WebhookProcessStatus> handler) {
        boolean shouldProcess = idempotencyService.tryBegin(event.eventId(), event.type(), event.livemode());
        if (!shouldProcess) {
            return true; // 真の重複（確定済み）。billing 所有なのでフォールバックはしない。
        }
        WebhookProcessStatus result;
        try {
            result = handler.get();
        } catch (RuntimeException e) {
            idempotencyService.markFailed(event.eventId());
            log.warn("F20.1 決済 Webhook ハンドラ失敗。FAILED 記録のうえ再送出します: eventId={}, type={}",
                    event.eventId(), event.type(), e);
            throw e;
        }
        idempotencyService.markProcessed(event.eventId(), result);
        return true;
    }

    private LocalDateTime toLdt(Long epochSec) {
        return epochSec == null ? null : LocalDateTime.ofInstant(Instant.ofEpochSecond(epochSec), clock.getZone());
    }
}
