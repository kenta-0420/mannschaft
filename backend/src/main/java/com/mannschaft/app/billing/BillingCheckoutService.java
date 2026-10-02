package com.mannschaft.app.billing;

import com.mannschaft.app.billing.BillingContractService.ContractResult;
import com.mannschaft.app.common.BusinessException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/**
 * F20.1 実決済（D-4・2026-07-10 御裁可）: 決済フロー契約のオーケストレーション。
 *
 * <p>「PENDING 契約起票（{@link BillingContractService#createPendingPaidContract}・トランザクション内）→
 * Stripe Checkout 生成（{@link BillingPaymentGateway}・トランザクション外の外部呼び出し）→ 失敗時は補償
 * （{@link BillingContractService#abandonPendingContract}）」を、外部 API を @Transactional に含めずに束ねる。</p>
 *
 * <p>{@code successUrl}/{@code cancelUrl} は {@code app.base-url} から組み立てる（FE ページは別部隊が作るため
 * URL のみ確定・F09.13 の {@code NotificationCreditCheckoutService} と同流儀）。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class BillingCheckoutService {

    private final BillingContractService billingContractService;
    private final BillingPaymentGateway billingPaymentGateway;

    @Value("${app.base-url}")
    private String appBaseUrl;

    /** 決済フロー起票の結果（PENDING 契約＋Checkout URL）。 */
    public record PaidCheckoutResult(ContractResult pending, String checkoutUrl) {}

    /**
     * 決済フロー契約を起票し Checkout を生成する（設計書 02）。
     *
     * @param priceJpy マスタ解決済みの月額（非 NULL・呼び出し側が価格解決で決済フローと判定済み）
     */
    public PaidCheckoutResult startPaidContract(
            EntitlementScopeKind scopeKind, Long scopeId, Long organizationId,
            ContractKind contractKind, String planKey, String featureKey, int priceJpy, Long operatorUserId) {

        // 1) PENDING 契約＋pointer を起票（tx1 commit・entitlements 未発行）。
        ContractResult pending = billingContractService.createPendingPaidContract(
                scopeKind, scopeId, organizationId, contractKind, planKey, featureKey, priceJpy, operatorUserId);

        // 2) Stripe Checkout 生成（外部呼び出し・tx 外）。失敗は補償して 015 で上申（症状を隠さない）。
        String displayName = buildDisplayName(contractKind, planKey, featureKey);
        String successUrl = appBaseUrl + "/billing/plans?checkout=success";
        String cancelUrl = appBaseUrl + "/billing/plans?checkout=cancelled";
        try {
            BillingPaymentGateway.CheckoutSessionInfo info = billingPaymentGateway.createSubscriptionCheckout(
                    operatorUserId, priceJpy, displayName, pending.contractId(), successUrl, cancelUrl);
            return new PaidCheckoutResult(pending, info.url());
        } catch (RuntimeException e) {
            // 孤児 PENDING を残さない: 契約を CANCELLED＋pointer 物理 DELETE で補償し、スロットを解放する。
            log.error("F20.1 決済: Checkout 生成失敗。PENDING 契約を補償します contractId={}", pending.contractId(), e);
            try {
                billingContractService.abandonPendingContract(pending.contractId());
            } catch (RuntimeException compensateEx) {
                log.error("F20.1 決済: PENDING 契約の補償にも失敗（要手動確認）contractId={}", pending.contractId(), compensateEx);
            }
            throw new BusinessException(EntitlementErrorCode.CHECKOUT_SESSION_FAILED, e);
        }
    }

    private String buildDisplayName(ContractKind contractKind, String planKey, String featureKey) {
        return contractKind == ContractKind.PLAN
                ? "Mannschaft プラン: " + planKey
                : "Mannschaft 機能: " + featureKey;
    }
}
