package com.mannschaft.app.billing.api;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.mannschaft.app.billing.BillingChangePreviewEntity;
import com.mannschaft.app.billing.BillingChangePreviewRepository;
import com.mannschaft.app.billing.BillingContractChangeKind;
import com.mannschaft.app.billing.BillingContractEntity;
import com.mannschaft.app.billing.BillingContractRepository;
import com.mannschaft.app.billing.BillingPlanChangeGateway;
import com.mannschaft.app.billing.BillingPriceBandVersionEntity;
import com.mannschaft.app.billing.BillingPriceBandVersionRepository;
import com.mannschaft.app.billing.BillingPriceVersionStatus;
import com.mannschaft.app.billing.BillingProductKind;
import com.mannschaft.app.billing.EntitlementErrorCode;
import com.mannschaft.app.billing.ScopeMemberCountService;
import com.mannschaft.app.billing.api.BillingConflictException.BillingConflictDetails;
import com.mannschaft.app.billing.api.BillingConflictException.Reason;
import com.mannschaft.app.billing.api.dto.BillingChangePreviewRequest;
import com.mannschaft.app.billing.api.dto.BillingChangePreviewResponse;
import com.mannschaft.app.common.BusinessException;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;

/**
 * Billing Center PR6b-1 A群: 事前見積り（{@code POST …/change-previews}）の中核サービス（AC-1〜5・
 * AC-17〜24。band 解決の一部は AC-19/20 と兼ねる）。
 *
 * <p><b>AC-2 の担保</b>: {@code amountDueNow} は {@link BillingPlanChangeGateway#previewPlanChange}
 * の戻り値をそのまま {@link BillingChangePreviewResponse.Money} へ写すだけで、こちらでは一切の
 * 日割り計算を行わない。band の金額（{@code amountIncludingTax}）は<b>upgrade 判定
 * （AC-22〜24）と再照合（AC-13/AC-15）専用</b>であり、応答の金額には使わない。</p>
 */
@Service
@RequiredArgsConstructor
public class BillingPlanChangePreviewService {

    /** AC-5/AC-6: 有効期限（最大10分・半開区間）。 */
    static final Duration PREVIEW_TTL = Duration.ofMinutes(10);

    /** AC-18: 期末まで最低限必要な残り時間（30分+60秒。BC-13 の Session 安全域と同じ流儀）。 */
    static final Duration MINIMUM_WINDOW_BEFORE_PERIOD_END = Duration.ofMinutes(30).plusSeconds(60);

    private final BillingContractRepository billingContractRepository;
    private final BillingChangePreviewRepository changePreviewRepository;
    private final BillingPriceBandVersionRepository bandRepository;
    private final BillingAccessGuard billingAccessGuard;
    private final ScopeMemberCountService scopeMemberCountService;
    private final BillingPlanChangeGateway planChangeGateway;
    private final ObjectMapper objectMapper;
    private final Clock clock;

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public BillingChangePreviewResponse preview(
            long actorId, UUID contractId, BillingChangePreviewRequest request, String requestBody) {
        BillingContractEntity contract = loadManageable(actorId, contractId);

        Instant now = clock.instant();
        if (request.toProductKind() != BillingProductKind.PLAN) {
            throw new BusinessException(EntitlementErrorCode.INVALID_CONTRACT_KIND);
        }

        Instant periodEndInstant = contract.getCurrentPeriodEnd() == null
                ? null : contract.getCurrentPeriodEnd().atZone(clock.getZone()).toInstant();
        // AC-18: 期末まで 30分+60秒 未満は 409（Stripe へは問い合わせない）。
        if (periodEndInstant != null
                && Duration.between(now, periodEndInstant).compareTo(MINIMUM_WINDOW_BEFORE_PERIOD_END) < 0) {
            throw new BillingConflictException(EntitlementErrorCode.MONTH_BOUNDARY,
                    new BillingConflictDetails(Reason.MONTH_BOUNDARY, periodEndInstant, null));
        }

        int memberCount = scopeMemberCountService.countActiveMembers(contract.getScopeKind(), contract.getScopeId());

        BillingPriceBandVersionEntity fromBand = resolveFromBand(contract, memberCount, now);
        BillingPriceBandVersionEntity toBand = resolveToBand(
                request.toProductKey(), contract, memberCount, now);

        // AC-22/23/24: 上位（＝より高額）でなければ upgrade として扱わない。
        if (toBand.getAmountIncludingTax() <= fromBand.getAmountIncludingTax()) {
            throw conflict(Reason.CHANGE_CONFLICT, null);
        }

        BillingPlanChangeGateway.PlanChangeQuote quote = planChangeGateway.previewPlanChange(
                new BillingPlanChangeGateway.PlanChangePreviewCommand(
                        contract.getPspSubscriptionRef(), toBand.getStripePriceRef(), memberCount));

        // AC-2/AC-4: 按分の基準日時は Stripe へ実際に渡した値を保存する。ここで now を採ると、
        // 適用時に proration_date として戻したときに見積りと違う基準で按分され、
        // 利用者へ見せた額と請求額がずれる。
        Instant prorationAt = quote.prorationAt() != null ? quote.prorationAt() : now;
        Instant periodStart = now;
        Instant periodEnd = periodEndInstant != null ? periodEndInstant
                : now.plus(30, java.time.temporal.ChronoUnit.DAYS);
        Instant expiresAt = now.plus(PREVIEW_TTL);

        BillingChangePreviewEntity preview = BillingChangePreviewEntity.builder()
                .actorId(actorId)
                .contractId(contract.getId())
                .billingCustomerId(contract.getBillingCustomerId())
                .organizationId(contract.getOrganizationId())
                .scopeKind(contract.getScopeKind())
                .scopeId(contract.getScopeId())
                .productKind(BillingProductKind.PLAN)
                .productKey(request.toProductKey())
                .fromPriceBandVersionId(fromBand.getId())
                .toPriceBandVersionId(toBand.getId())
                .memberCount(memberCount)
                .taxSnapshot(writeJson(new TaxSnapshot(toBand.getTaxNameSnapshot(), toBand.getTaxRateBasisPoints())))
                .amountSnapshot(writeJson(new AmountSnapshot(
                        fromBand.getAmountIncludingTax(), toBand.getAmountIncludingTax(),
                        quote.amountDueNow(), quote.currency())))
                .periodStart(periodStart)
                .periodEnd(periodEnd)
                .prorationAt(prorationAt)
                .contractVersion(contract.getVersion())
                .requestHash(sha256(requestBody))
                .expiresAt(expiresAt)
                .version(0L)
                .build();
        changePreviewRepository.save(preview);

        return new BillingChangePreviewResponse(
                preview.getId(),
                BillingContractChangeKind.UPGRADE,
                new BillingChangePreviewResponse.Money(
                        quote.currency(), quote.amountDueNow(), quote.amountExcludingTax(),
                        quote.taxAmount(), quote.taxName(), quote.taxRateBasisPoints()),
                quote.prorationAt() != null ? quote.prorationAt() : now,
                expiresAt);
    }

    // ============================================================
    // band 解決（AC-19/AC-20）
    // ============================================================

    /** AC-19: {@code price_band_version_id} が NULL の既存契約は現在人数から解決する。 */
    private BillingPriceBandVersionEntity resolveFromBand(
            BillingContractEntity contract, int memberCount, Instant now) {
        if (contract.getPriceBandVersionId() != null) {
            return bandRepository.findByIdAndDeletedAtIsNull(contract.getPriceBandVersionId())
                    .orElseThrow(() -> conflict(Reason.CHANGE_CONFLICT, null));
        }
        List<BillingPriceBandVersionEntity> candidates = bandRepository.findEffectiveCandidates(
                BillingProductKind.PLAN, contract.getPlanKey(), contract.getScopeKind(),
                List.of(BillingPriceVersionStatus.ACTIVE), now, memberCount);
        if (candidates.isEmpty()) {
            // AC-19b: NOT NULL 制約違反を 500 として漏らさず 409 CHANGE_CONFLICT で畳む。
            throw conflict(Reason.CHANGE_CONFLICT, null);
        }
        return candidates.get(0);
    }

    /** AC-20a/b/c: target band が不在・非ACTIVE・Stripe Price ref 無しはいずれも 409。 */
    private BillingPriceBandVersionEntity resolveToBand(
            String toProductKey, BillingContractEntity contract, int memberCount, Instant now) {
        List<BillingPriceBandVersionEntity> candidates = bandRepository.findEffectiveCandidates(
                BillingProductKind.PLAN, toProductKey, contract.getScopeKind(),
                List.of(BillingPriceVersionStatus.ACTIVE), now, memberCount);
        if (candidates.isEmpty()) {
            throw conflict(Reason.CHANGE_CONFLICT, null);
        }
        BillingPriceBandVersionEntity band = candidates.get(0);
        if (band.getStripePriceRef() == null || band.getStripePriceRef().isBlank()) {
            throw conflict(Reason.CHANGE_CONFLICT, null);
        }
        return band;
    }

    // ============================================================
    // 認可
    // ============================================================

    private BillingContractEntity loadManageable(long actorId, UUID contractId) {
        BillingContractEntity contract = billingContractRepository.findByIdAndDeletedAtIsNull(contractId)
                .orElseThrow(() -> new BusinessException(EntitlementErrorCode.CONTRACT_NOT_FOUND));
        if (billingAccessGuard.canManageByActorId(actorId, contract.getScopeKind(), contract.getScopeId())) {
            return contract;
        }
        throw billingAccessGuard.isScopeMember(actorId, contract.getScopeKind(), contract.getScopeId())
                ? new BusinessException(EntitlementErrorCode.SCOPE_FORBIDDEN)
                : new BusinessException(EntitlementErrorCode.CONTRACT_NOT_FOUND);
    }

    // ============================================================
    // ヘルパ
    // ============================================================

    static BillingConflictException conflict(Reason reason, Instant availableAt) {
        return new BillingConflictException(EntitlementErrorCode.CHANGE_CONFLICT,
                new BillingConflictDetails(reason, availableAt, null));
    }

    private String writeJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("failed to serialize billing preview snapshot", e);
        }
    }

    private String sha256(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(
                    digest.digest((value == null ? "" : value).getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is unavailable", e);
        }
    }

    /** band の税スナップショット（AC-15 の再照合に使う）。 */
    record TaxSnapshot(String taxName, Integer taxRateBasisPoints) {
    }

    /** band の金額スナップショット（AC-13 の再照合に使う）。 */
    record AmountSnapshot(long fromAmountIncludingTax, long toAmountIncludingTax,
                          long stripeAmountDueNow, String currency) {
    }
}
