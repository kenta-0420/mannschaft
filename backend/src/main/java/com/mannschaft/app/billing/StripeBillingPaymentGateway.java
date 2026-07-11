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
 * Entity/Repository 依存をカプセル化・email プレースホルダの P1 既知負債も同所に集約）だけを呼ぶ。</p>
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
}
