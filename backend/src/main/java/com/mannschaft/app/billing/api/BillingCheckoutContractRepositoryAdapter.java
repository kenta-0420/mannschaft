package com.mannschaft.app.billing.api;

import com.mannschaft.app.billing.ActiveContractPointerEntity;
import com.mannschaft.app.billing.ActiveContractPointerRepository;
import com.mannschaft.app.billing.BillingContractEntity;
import com.mannschaft.app.billing.BillingContractRepository;
import com.mannschaft.app.billing.BillingContractService;
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
    /** PENDING 契約の放棄（補償）は既存決済フローと同じ正本 {@link BillingContractService} に委ねる。 */
    private final BillingContractService billingContractService;
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
                .payerUserId(actorId)
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
     * Stripe Checkout Session を契約へ紐付ける（V198 {@code billing_contracts.stripe_checkout_session_ref}）。
     *
     * <p>条件付き UPDATE（CAS）で書き込む。条件は「対象契約が実在し、論理削除されておらず、status=PENDING で
     * あり、Session ref が未設定か同一 ref であること」。同一 ref の再送は 1 行更新として通る（冪等）ため、
     * <b>再送時に「この契約は既に Session を持つ」を DB だけで判定でき</b>、既に別 Session を持つ契約への
     * 上書き＝Stripe の二重 Session 作成をアプリ側で確実に弾ける。「別契約が同じ Session を握る」経路は
     * {@code uk_bc_checkout_session} が DB 側で塞ぐ。</p>
     *
     * <p>更新行数が 1 でなければ握りつぶさず例外を投げ、呼び出し元の照合キュー退避＋502 経路へ倒す。
     * {@code psp_subscription_ref} は webhook の Subscription 逆引き専用（F08.9 会費との分離キー）ゆえ
     * 流用しない。</p>
     */
    @Override
    @Transactional
    public void attachStripeSession(UUID contractId, String stripeSessionId) {
        if (contractId == null || stripeSessionId == null || stripeSessionId.isBlank()) {
            throw new IllegalArgumentException("contractId and stripeSessionId must not be blank");
        }
        LocalDateTime now = LocalDateTime.now(clock.withZone(UserZoneLocalDateTimeParser.SERVER_ZONE));
        int updated;
        try {
            updated = billingContractRepository
                    .attachCheckoutSessionIfPending(contractId, stripeSessionId, now);
        } catch (DataIntegrityViolationException e) {
            // uk_bc_checkout_session 違反 = 同一 Session を別契約が既に握っている。
            throw new IllegalStateException(
                    "stripe checkout session is already attached to another billing contract", e);
        }
        if (updated != 1) {
            throw new IllegalStateException(
                    "billing contract is not attachable (missing, not PENDING, or bound to another session)");
        }
    }

    /**
     * Stripe Checkout Session の作成に失敗したときに PENDING 契約を解放する（BC-13 の補償）。
     *
     * <p>既存決済フロー（{@code BillingCheckoutService#startPaidContract}）と<b>同じ流儀</b>で、
     * {@link BillingContractService#abandonPendingContract} へ委ねる（CANCELLED 化 ＋
     * {@code active_contract_pointers} の物理 DELETE）。ここを自前で書くと解放条件が二重管理になり、
     * 片方だけ直る事故を招くため委譲に徹する。PENDING 以外は no-op で冪等。</p>
     *
     * <p>これが無いと、Stripe 作成が落ちるたびに孤児 PENDING がスロットを占有し、
     * {@code uk_acp_slot} により<b>当該 scope の以後の購入が永久に 016 で詰む</b>
     * （Session が存在しないため {@code checkout.session.expired} でも解放されない）。</p>
     */
    @Override
    @Transactional
    public void abandonPendingContract(UUID contractId) {
        if (contractId == null) {
            return;
        }
        billingContractService.abandonPendingContract(contractId);
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
