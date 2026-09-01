package com.mannschaft.app.billing.api;

import com.mannschaft.app.billing.ActiveContractPointerEntity;
import com.mannschaft.app.billing.ActiveContractPointerRepository;
import com.mannschaft.app.billing.BillingContractEntity;
import com.mannschaft.app.billing.BillingContractRepository;
import com.mannschaft.app.billing.BillingProductKind;
import com.mannschaft.app.billing.ContractKind;
import com.mannschaft.app.billing.ContractStatus;
import com.mannschaft.app.billing.EntitlementErrorCode;
import com.mannschaft.app.billing.EntitlementScopeKind;
import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.common.timezone.UserZoneLocalDateTimeParser;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.LocalDateTime;
import java.util.UUID;

/**
 * {@link BillingCheckoutContractRepository} の JPA 実装（PR4 Checkout の PENDING 起票）。
 *
 * <p>既存の決済フロー（{@code BillingContractService#createPendingPaidContract}）と同じく
 * 「契約行 INSERT → {@code active_contract_pointers} INSERT でスロット確保」の順で起票し、
 * {@code uk_acp_slot} の UNIQUE 違反で並行二重 Checkout を物理的に拒否する。V196 で追加した
 * {@code billing_customer_id} / {@code price_band_version_id} / {@code version} は quote から焼き付ける。</p>
 */
@Component
@RequiredArgsConstructor
class BillingCheckoutContractRepositoryAdapter implements BillingCheckoutContractRepository {

    private final BillingContractRepository billingContractRepository;
    private final ActiveContractPointerRepository activeContractPointerRepository;
    private final Clock clock;

    @Override
    @Transactional
    public UUID reservePendingContract(BillingQuoteSnapshot quote, long actorId) {
        ContractKind contractKind = quote.productKind() == BillingProductKind.ADDON
                ? ContractKind.ADDON : ContractKind.PLAN;
        boolean addon = contractKind == ContractKind.ADDON;
        String slotAddonKey = addon ? quote.productKey() : "";
        Long organizationId = quote.scopeKind() == EntitlementScopeKind.ORG ? quote.scopeId() : null;
        LocalDateTime now = LocalDateTime.now(clock.withZone(UserZoneLocalDateTimeParser.SERVER_ZONE));

        BillingContractEntity contract = BillingContractEntity.builder()
                .scopeKind(quote.scopeKind())
                .scopeId(quote.scopeId())
                .organizationId(organizationId)
                .contractKind(contractKind)
                .planKey(addon ? null : quote.productKey())
                .featureKey(addon ? quote.productKey() : null)
                .status(ContractStatus.PENDING)
                .memberCountSnapshot(quote.scopeKind() == EntitlementScopeKind.USER
                        ? null : quote.memberCount())
                .priceJpySnapshot(monthlyPriceJpy(quote))
                .billingCustomerId(quote.billingCustomerId())
                .priceBandVersionId(quote.priceBandVersionId())
                .version(0L)
                .contractedAt(now)
                .createdBy(actorId)
                .build();
        BillingContractEntity saved = billingContractRepository.save(contract);

        ActiveContractPointerEntity pointer = ActiveContractPointerEntity.builder()
                .scopeKind(quote.scopeKind())
                .scopeId(quote.scopeId())
                .contractKind(contractKind)
                .addonFeatureKey(slotAddonKey)
                .contractId(saved.getId())
                .organizationId(organizationId)
                .build();
        try {
            activeContractPointerRepository.saveAndFlush(pointer);
        } catch (DataIntegrityViolationException e) {
            throw slotConflict(quote, contractKind, slotAddonKey, e);
        }
        return saved.getId();
    }

    /**
     * Stripe Checkout Session を契約へ紐付ける。
     *
     * <p><b>現状</b>: {@code billing_contracts} には Checkout Session 専用列が無く（V196・設計書 05 とも
     * 未定義）、{@code psp_subscription_ref} は webhook の Subscription 逆引き専用（{@code uk_bc_psp_subscription}）
     * のため流用できない。そこで本メソッドは「予約した PENDING 契約が実在すること」を検証するに留める。
     * 検証に失敗した場合は握りつぶさず例外を投げ、呼び出し元の照合キュー退避（Stripe 側 Session の回収）へ倒す。
     * Session ↔ 契約の突合自体は Stripe metadata（{@code billingContractId}）と
     * {@code stripe_webhook_events.billing_contract_id} で成立する。</p>
     */
    @Override
    @Transactional
    public void attachStripeSession(UUID contractId, String stripeSessionId) {
        if (contractId == null || stripeSessionId == null || stripeSessionId.isBlank()) {
            throw new IllegalArgumentException("contractId and stripeSessionId must not be blank");
        }
        BillingContractEntity contract = billingContractRepository.findByIdAndDeletedAtIsNull(contractId)
                .orElseThrow(() -> new IllegalStateException("pending billing contract not found"));
        if (contract.getStatus() != ContractStatus.PENDING) {
            throw new IllegalStateException("billing contract is no longer pending");
        }
    }

    /** 翌月満額（税込・円）を契約 snapshot へ焼き付ける。 */
    private int monthlyPriceJpy(BillingQuoteSnapshot quote) {
        BillingMoney monthly = quote.nextMonthlyTotal();
        if (monthly == null) {
            throw new BusinessException(EntitlementErrorCode.PRICE_NOT_SELLABLE);
        }
        return Math.toIntExact(monthly.amountIncludingTax());
    }

    /** 既存スロットが PENDING（入金前）なら 016、それ以外（ACTIVE 等）は 006 を返す。 */
    private BusinessException slotConflict(BillingQuoteSnapshot quote, ContractKind contractKind,
                                           String slotAddonKey, DataIntegrityViolationException cause) {
        ContractStatus existing = activeContractPointerRepository
                .findByScopeKindAndScopeIdAndContractKindAndAddonFeatureKey(
                        quote.scopeKind(), quote.scopeId(), contractKind, slotAddonKey)
                .flatMap(pointer -> billingContractRepository.findByIdAndDeletedAtIsNull(pointer.getContractId()))
                .map(BillingContractEntity::getStatus)
                .orElse(null);
        if (existing == ContractStatus.PENDING) {
            return new BusinessException(EntitlementErrorCode.CONTRACT_PENDING_PAYMENT, cause);
        }
        return new BusinessException(EntitlementErrorCode.CONTRACT_ALREADY_ACTIVE, cause);
    }
}
