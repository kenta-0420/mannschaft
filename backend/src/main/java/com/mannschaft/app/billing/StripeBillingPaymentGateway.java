package com.mannschaft.app.billing;

import com.mannschaft.app.payment.service.PaymentMethodService;
import com.mannschaft.app.payment.stripe.StripePaymentProvider;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.UUID;

/**
 * F20.1 実決済（D-1〜D-4・2026-07-10 御裁可）: {@link BillingPaymentGateway} の Stripe 実装。
 *
 * <p>Stripe SDK 依存は payment ドメインの {@link StripePaymentProvider} に封じ込め、本クラスは
 * (1) 決済者の Stripe Customer get-or-create を payment ドメインの公開サービスへ委譲し、
 * (2) {@code Mode.SUBSCRIPTION} の Checkout 生成／期末解約予約／即時解約を委譲する。Connect は用いない（D-2）。</p>
 *
 * <p><b>クロスドメイン Entity 依存の禁止（ArchUnit D-1・CI 差し戻し対応）:</b> billing ドメインから
 * payment の {@code StripeCustomerEntity}／auth の {@code UserEntity} を直接参照してはならない
 * （{@code CrossDomainEntityImportArchTest}・CLAUDE.md「ドメイン間のデータ取得は Service のメソッド呼び出し経由」）。
 * Customer の get-or-create は {@link PaymentMethodService#getOrCreateStripeCustomerId(Long)}（payment ドメイン内に
 * Entity/Repository 依存をカプセル化）だけを呼ぶ。【残債2】Customer 新規作成時の email は
 * {@code PaymentMethodService} 側で実メールへ根治済み（P1 {@code MemberPaymentService} 側のプレースホルダ
 * 既知負債は本修正のスコープ外）。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class StripeBillingPaymentGateway implements BillingPaymentGateway {

    private final StripePaymentProvider stripePaymentProvider;
    private final PaymentMethodService paymentMethodService;

    // 【検分4番対応】@Transactional を付けない: 本メソッドは外部 HTTP を最大2回
    // （Customer 生成＋Checkout Session 生成）呼ぶため、DB トランザクションに抱えると
    // 接続を長時間占有する。stripe_customers の保存は PaymentMethodService 側の最小 tx で完結する。
    @Override
    public CheckoutSessionInfo createSubscriptionCheckout(
            Long operatorUserId, int priceJpy, String displayName, UUID contractId,
            String successUrl, String cancelUrl) {

        // payment ドメインの公開 API 経由で get-or-create（Entity 直接参照の禁止・ArchUnit D-1）。
        String stripeCustomerId = paymentMethodService.getOrCreateStripeCustomerId(operatorUserId);

        StripePaymentProvider.CheckoutSessionInfo info =
                stripePaymentProvider.createBillingSubscriptionCheckoutSession(
                        stripeCustomerId, priceJpy, displayName, contractId.toString(), successUrl, cancelUrl);
        return new CheckoutSessionInfo(info.sessionId(), info.checkoutUrl());
    }

    @Override
    public Instant cancelAtPeriodEnd(String subscriptionRef) {
        StripePaymentProvider.SubscriptionInfo info = stripePaymentProvider.cancelSubscriptionAtPeriodEnd(
                subscriptionRef, "billing-cancel-" + subscriptionRef);
        Long currentPeriodEnd = info.currentPeriodEnd();
        return currentPeriodEnd == null ? null : Instant.ofEpochSecond(currentPeriodEnd);
    }

    @Override
    public void cancelImmediately(String subscriptionRef) {
        stripePaymentProvider.cancelBillingSubscriptionImmediately(
                subscriptionRef, "billing-purge-" + subscriptionRef);
    }

    // ========================================
    // 柱③-B PR-2 請求支払者の引継（設計書 billing_payer_handover_design.md §3.2・§3.4・§3.6）
    // ========================================

    /** 引継の新サブスク作成の冪等キー接頭辞（設計書 §3.4）。 */
    private static final String KEY_CREATE = "billing-handover-create-";
    /** 承諾確定時の旧サブスク期末解約予約の冪等キー接頭辞（通常解約 {@code billing-cancel-*} と別名前空間・AC-24）。 */
    private static final String KEY_SCHEDULE_CANCEL = "billing-handover-schedule-cancel-";
    /** FAILED 確定時の旧サブスク差し戻しの冪等キー接頭辞（設計書 §3.4）。 */
    private static final String KEY_REVERT_CANCEL = "billing-handover-revert-cancel-";
    /** 新 trial サブスク即時解約の冪等キー接頭辞（設計書 §3.4）。 */
    private static final String KEY_CANCEL_NEW = "billing-handover-cancel-new-";

    /**
     * 回収対象から除外する Stripe ステータス（設計書 §3.2 の回復経路）。
     *
     * <p>{@code canceled}／{@code incomplete_expired} は Stripe 側で<b>既に死んでいる</b>サブスクであり、
     * 再利用しても復旧できない（{@code cancel_at_period_end} の差し戻しも trial 継続もできない）。
     * これらを「作成済み」として拾うと、正常に作り直すべき場面で作成をスキップし引継が永久に進まなくなるため、
     * 突合対象から外して<b>新規作成の判断を妨げない</b>。</p>
     */
    private static final java.util.Set<String> DEAD_SUBSCRIPTION_STATUSES =
            java.util.Set.of("canceled", "incomplete_expired");

    // createSubscriptionCheckout と同じ理由で @Transactional を付けない（外部 HTTP を抱えない）。
    @Override
    public CheckoutSessionInfo createHandoverSubscriptionCheckout(
            Long newPayerUserId, int priceJpy, String displayName,
            UUID newContractId, UUID oldContractId, UUID handoverRequestId,
            Instant trialEnd, String successUrl, String cancelUrl) {

        String stripeCustomerId = paymentMethodService.getOrCreateStripeCustomerId(newPayerUserId);

        StripePaymentProvider.CheckoutSessionInfo info =
                stripePaymentProvider.createBillingHandoverSubscriptionCheckoutSession(
                        stripeCustomerId, priceJpy, displayName,
                        newContractId.toString(), handoverRequestId.toString(), oldContractId.toString(),
                        trialEnd.getEpochSecond(), successUrl, cancelUrl,
                        KEY_CREATE + handoverRequestId);
        return new CheckoutSessionInfo(info.sessionId(), info.checkoutUrl());
    }

    @Override
    public Instant scheduleCancelAtPeriodEndForHandover(String subscriptionRef, UUID handoverRequestId) {
        StripePaymentProvider.SubscriptionInfo info = stripePaymentProvider.cancelSubscriptionAtPeriodEnd(
                subscriptionRef, KEY_SCHEDULE_CANCEL + handoverRequestId);
        Long currentPeriodEnd = info.currentPeriodEnd();
        return currentPeriodEnd == null ? null : Instant.ofEpochSecond(currentPeriodEnd);
    }

    @Override
    public void revertCancelAtPeriodEndForHandover(String subscriptionRef, UUID handoverRequestId) {
        stripePaymentProvider.revertSubscriptionCancelAtPeriodEnd(
                subscriptionRef, KEY_REVERT_CANCEL + handoverRequestId);
    }

    @Override
    public void cancelHandoverNewSubscription(String subscriptionRef, UUID handoverRequestId) {
        stripePaymentProvider.cancelBillingSubscriptionImmediately(
                subscriptionRef, KEY_CANCEL_NEW + handoverRequestId);
    }

    @Override
    public SubscriptionSnapshot retrieveSubscription(String subscriptionRef) {
        StripePaymentProvider.SubscriptionDetail detail =
                stripePaymentProvider.retrieveSubscriptionDetail(subscriptionRef);
        return new SubscriptionSnapshot(
                detail.subscriptionId(),
                detail.status(),
                detail.cancelAtPeriodEnd(),
                toInstant(detail.currentPeriodStart()),
                toInstant(detail.currentPeriodEnd()),
                detail.pendingSetupIntentId());
    }

    @Override
    public java.util.Optional<String> findHandoverSubscriptionRef(Long newPayerUserId, UUID handoverRequestId) {
        String stripeCustomerId = paymentMethodService.getOrCreateStripeCustomerId(newPayerUserId);
        String expected = handoverRequestId.toString();
        return stripePaymentProvider.listSubscriptionsByCustomer(stripeCustomerId).stream()
                .filter(d -> !DEAD_SUBSCRIPTION_STATUSES.contains(d.status()))
                .filter(d -> d.metadata() != null && expected.equals(d.metadata().get("handoverRequestId")))
                .map(StripePaymentProvider.SubscriptionDetail::subscriptionId)
                .findFirst();
    }

    @Override
    public boolean hasUsablePaymentMethod(Long userId) {
        // 外部 HTTP を呼ばず DB 参照のみで判定する（Customer の新規作成という副作用も起こさない・設計書 §3.6）。
        return paymentMethodService.hasDefaultPaymentMethod(userId);
    }

    /** Stripe 由来の unix 秒を {@link Instant} へ変換する（null は null のまま）。 */
    private Instant toInstant(Long epochSec) {
        return epochSec == null ? null : Instant.ofEpochSecond(epochSec);
    }
}
