package com.mannschaft.app.billing.api;

import com.mannschaft.app.billing.BillingPriceBandVersionEntity;
import com.mannschaft.app.billing.BillingPriceBandVersionRepository;
import com.mannschaft.app.billing.BillingPriceCreationSource;
import com.mannschaft.app.billing.BillingPriceVersionEntity;
import com.mannschaft.app.billing.BillingPriceVersionRepository;
import com.mannschaft.app.billing.BillingPriceVersionStatus;
import com.mannschaft.app.billing.BillingProductKind;
import com.mannschaft.app.billing.EntitlementScopeKind;
import com.mannschaft.app.billing.FeatureCatalogEntity;
import com.mannschaft.app.billing.FeatureCatalogRepository;
import com.mannschaft.app.billing.PlanRepository;
import com.mannschaft.app.billing.PriceRevisionErrorCode;
import com.mannschaft.app.billing.api.dto.PriceBandInput;
import com.mannschaft.app.billing.api.dto.PriceRevisionBandResponse;
import com.mannschaft.app.billing.api.dto.PriceRevisionCreateRequest;
import com.mannschaft.app.billing.api.dto.PriceRevisionResponse;
import com.mannschaft.app.billing.tax.BillingTaxCodeEntity;
import com.mannschaft.app.billing.tax.BillingTaxCodeService;
import com.mannschaft.app.billing.tax.BillingTaxDerivationResult;
import com.mannschaft.app.billing.tax.BillingTaxDerivationService;
import com.mannschaft.app.auth.AuditEventType;
import com.mannschaft.app.auth.service.AuditLogService;
import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.common.UuidV7;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * {@code POST /price-revisions} 作成ロジック（決定4・決定8）。
 *
 * <p>コンストラクタ引数順・DTO の種別（record/Lombok クラス）は試練隊（第1陣）が固定した契約であり、
 * 変更していない。</p>
 *
 * <p>正本: {@code .claude/campaigns/price-rev-plan-v3.md} 決定4・決定8・AC-17〜AC-53・AC-176・AC-177。</p>
 */
@Service
public class PriceRevisionCreateService {

    private static final int MAX_BANDS = 10;
    private static final int MAX_FIELD_LENGTH = 64;
    private static final long MIN_INPUT_AMOUNT = 1L;
    private static final long MAX_INPUT_AMOUNT = 9_999_999L;

    /** future 予約とみなす状態（マスター裁可・第6版: 同時に1本まで）。 */
    private static final Set<BillingPriceVersionStatus> FUTURE_STATUSES =
            EnumSet.of(BillingPriceVersionStatus.DRAFT, BillingPriceVersionStatus.READY,
                    BillingPriceVersionStatus.SCHEDULED);

    private final BillingPriceVersionRepository priceVersionRepository;
    private final BillingPriceBandVersionRepository bandVersionRepository;
    private final PlanRepository planRepository;
    private final FeatureCatalogRepository featureCatalogRepository;
    private final BillingTaxCodeService taxCodeService;
    private final BillingTaxDerivationService taxDerivationService;
    private final Clock clock;
    private final AuditLogService auditLogService;

    public PriceRevisionCreateService(
            BillingPriceVersionRepository priceVersionRepository,
            BillingPriceBandVersionRepository bandVersionRepository,
            PlanRepository planRepository,
            FeatureCatalogRepository featureCatalogRepository,
            BillingTaxCodeService taxCodeService,
            BillingTaxDerivationService taxDerivationService,
            Clock clock,
            AuditLogService auditLogService) {
        this.priceVersionRepository = priceVersionRepository;
        this.bandVersionRepository = bandVersionRepository;
        this.planRepository = planRepository;
        this.featureCatalogRepository = featureCatalogRepository;
        this.taxCodeService = taxCodeService;
        this.taxDerivationService = taxDerivationService;
        this.clock = clock;
        this.auditLogService = auditLogService;
    }

    @Transactional
    public PriceRevisionResponse create(PriceRevisionCreateRequest request, Long adminId) {
        validateProductKeyLength(request.productKey());
        List<PriceBandInput> bands = validateAndSortBands(request.bands());
        Instant now = clock.instant();
        validateEffectivePeriod(request.effectiveFrom(), request.effectiveUntil(), now);
        validateProductExists(request.productKind(), request.productKey());

        BillingProductKind productKind = request.productKind();
        String productKey = request.productKey();
        EntitlementScopeKind scopeKind = request.scopeKind();

        // AC-52/53: 同一 (productKind, productKey, scopeKind) の revision を row lock してから
        // 単一 future 制限・overlap を判定する。
        List<BillingPriceVersionEntity> existing =
                priceVersionRepository.findAllForUpdate(productKind, productKey, scopeKind);

        boolean futureExists = existing.stream().anyMatch(e -> FUTURE_STATUSES.contains(e.getStatus()));
        if (futureExists) {
            throw new BusinessException(PriceRevisionErrorCode.FUTURE_REVISION_ALREADY_EXISTS);
        }

        assertNoActiveOverlap(existing, request.effectiveFrom(), request.effectiveUntil());

        long nextRevisionNo = priceVersionRepository
                .findByProductKindAndProductKeyAndScopeKindAndDeletedAtIsNullOrderByRevisionNoDesc(
                        productKind, productKey, scopeKind)
                .stream()
                .findFirst()
                .map(BillingPriceVersionEntity::getRevisionNo)
                .orElse(0L) + 1;

        UUID revisionId = UuidV7.generate();
        String catalogRevision = "REV-" + UuidV7.generate();

        BillingPriceVersionEntity revision = BillingPriceVersionEntity.builder()
                .id(revisionId)
                .productKind(productKind)
                .productKey(productKey)
                .scopeKind(scopeKind)
                .catalogRevision(catalogRevision)
                .revisionNo(nextRevisionNo)
                .status(BillingPriceVersionStatus.DRAFT)
                .effectiveFrom(request.effectiveFrom())
                .effectiveUntil(request.effectiveUntil())
                .createdBy(adminId)
                .creationSource(BillingPriceCreationSource.OPERATOR)
                .build();
        priceVersionRepository.save(revision);

        List<BillingPriceBandVersionEntity> bandEntities = new ArrayList<>();
        List<PriceRevisionBandResponse> bandResponses = new ArrayList<>();
        for (PriceBandInput input : bands) {
            BillingTaxCodeEntity taxCode = taxCodeService.resolveEffective(input.taxCode(), request.effectiveFrom());
            BillingTaxDerivationResult tax = taxDerivationService.derive(
                    input.inputAmount(), input.taxBehavior(), taxCode);

            BillingPriceBandVersionEntity bandEntity = BillingPriceBandVersionEntity.builder()
                    .productKind(productKind)
                    .productKey(productKey)
                    .scopeKind(scopeKind)
                    .bandNo(input.bandNo())
                    .minMembers(input.minMembers())
                    .maxMembers(input.maxMembers())
                    .priceVersionId(revisionId)
                    .inputAmount(input.inputAmount())
                    .taxBehavior(input.taxBehavior())
                    .taxCodeSnapshot(tax.getTaxCodeSnapshot())
                    .taxMasterSnapshot(tax.getTaxMasterSnapshot())
                    .amountExcludingTax(tax.getAmountExcludingTax())
                    .taxAmount(tax.getTaxAmount())
                    .taxRateBasisPoints(tax.getTaxRateBasisPoints())
                    .taxNameSnapshot(tax.getTaxNameSnapshot())
                    .includedInPrice(tax.isIncludedInPrice())
                    .amountIncludingTax(tax.getAmountIncludingTax())
                    .effectiveFrom(request.effectiveFrom())
                    .effectiveUntil(request.effectiveUntil())
                    .status(BillingPriceVersionStatus.DRAFT)
                    .createdBy(adminId)
                    .creationSource(BillingPriceCreationSource.OPERATOR)
                    .build();
            bandEntities.add(bandEntity);
        }
        bandVersionRepository.saveAll(bandEntities);

        for (BillingPriceBandVersionEntity bandEntity : bandEntities) {
            bandResponses.add(PriceRevisionBandResponse.builder()
                    .id(bandEntity.getId())
                    .bandNo(bandEntity.getBandNo())
                    .minMembers(bandEntity.getMinMembers())
                    .maxMembers(bandEntity.getMaxMembers())
                    .inputAmount(bandEntity.getInputAmount())
                    .taxBehavior(bandEntity.getTaxBehavior())
                    .taxCode(bandEntity.getTaxCodeSnapshot())
                    .amountExcludingTax(bandEntity.getAmountExcludingTax())
                    .taxAmount(bandEntity.getTaxAmount())
                    .amountIncludingTax(bandEntity.getAmountIncludingTax())
                    .taxRateBasisPoints(bandEntity.getTaxRateBasisPoints())
                    .status(bandEntity.getStatus())
                    .build());
        }

        // L群AC-171: 監査記録。Stripe Price ref・税額の途中値は載せない
        // （AC-168。未provisionのDRAFT時点ではまだ存在しないが、迷うものは載せない側に倒す）。
        auditLogService.record(AuditEventType.PRICE_REVISION_CREATED.name(), adminId, null, null, null,
                null, null, null,
                "{\"revisionId\":\"" + revisionId + "\",\"productKind\":\"" + productKind
                        + "\",\"productKey\":\"" + productKey + "\",\"scopeKind\":\"" + scopeKind + "\"}");

        return PriceRevisionResponse.builder()
                .id(revisionId)
                .productKind(productKind)
                .productKey(productKey)
                .scopeKind(scopeKind)
                .revisionNo(nextRevisionNo)
                .catalogRevision(catalogRevision)
                .status(BillingPriceVersionStatus.DRAFT)
                .effectiveFrom(request.effectiveFrom())
                .effectiveUntil(request.effectiveUntil())
                .bands(bandResponses)
                .lockVersion(revision.getLockVersion())
                .build();
    }

    private void validateProductKeyLength(String productKey) {
        if (productKey == null || productKey.isBlank() || productKey.length() > MAX_FIELD_LENGTH) {
            throw new BusinessException(PriceRevisionErrorCode.INVALID_FIELD_LENGTH);
        }
    }

    private List<PriceBandInput> validateAndSortBands(List<PriceBandInput> bands) {
        if (bands == null || bands.isEmpty()) {
            throw new BusinessException(PriceRevisionErrorCode.BAND_VALIDATION_FAILED);
        }
        if (bands.size() > MAX_BANDS) {
            throw new BusinessException(PriceRevisionErrorCode.BAND_VALIDATION_FAILED);
        }

        for (PriceBandInput band : bands) {
            String taxCode = band.taxCode();
            if (taxCode == null || taxCode.isBlank() || taxCode.length() > MAX_FIELD_LENGTH) {
                throw new BusinessException(PriceRevisionErrorCode.INVALID_FIELD_LENGTH);
            }
        }

        List<PriceBandInput> sorted = bands.stream()
                .sorted(Comparator.comparingInt(PriceBandInput::bandNo))
                .toList();

        for (int i = 0; i < sorted.size(); i++) {
            if (sorted.get(i).bandNo() != i + 1) {
                throw new BusinessException(PriceRevisionErrorCode.BAND_VALIDATION_FAILED);
            }
        }

        Integer prevMax = null;
        for (int i = 0; i < sorted.size(); i++) {
            PriceBandInput band = sorted.get(i);
            boolean isLast = (i == sorted.size() - 1);

            if (band.minMembers() < 1) {
                throw new BusinessException(PriceRevisionErrorCode.BAND_VALIDATION_FAILED);
            }
            if (band.maxMembers() != null && band.maxMembers() < band.minMembers()) {
                throw new BusinessException(PriceRevisionErrorCode.BAND_VALIDATION_FAILED);
            }
            if (!isLast && band.maxMembers() == null) {
                throw new BusinessException(PriceRevisionErrorCode.BAND_VALIDATION_FAILED);
            }
            if (isLast && band.maxMembers() != null) {
                throw new BusinessException(PriceRevisionErrorCode.BAND_VALIDATION_FAILED);
            }
            if (prevMax != null && band.minMembers() != prevMax + 1) {
                throw new BusinessException(PriceRevisionErrorCode.BAND_VALIDATION_FAILED);
            }
            prevMax = band.maxMembers();
        }

        for (PriceBandInput band : sorted) {
            if (band.inputAmount() < MIN_INPUT_AMOUNT || band.inputAmount() > MAX_INPUT_AMOUNT) {
                throw new BusinessException(PriceRevisionErrorCode.INVALID_AMOUNT);
            }
        }

        return sorted;
    }

    private void validateEffectivePeriod(Instant effectiveFrom, Instant effectiveUntil, Instant now) {
        if (effectiveFrom == null || effectiveFrom.isBefore(now)) {
            throw new BusinessException(PriceRevisionErrorCode.INVALID_EFFECTIVE_PERIOD);
        }
        if (effectiveUntil != null && !effectiveUntil.isAfter(effectiveFrom)) {
            throw new BusinessException(PriceRevisionErrorCode.INVALID_EFFECTIVE_PERIOD);
        }
    }

    private void validateProductExists(BillingProductKind productKind, String productKey) {
        if (productKind == BillingProductKind.PLAN) {
            if (!planRepository.existsById(productKey)) {
                throw new BusinessException(PriceRevisionErrorCode.PRODUCT_NOT_FOUND);
            }
        } else {
            FeatureCatalogEntity feature = featureCatalogRepository.findById(productKey)
                    .orElseThrow(() -> new BusinessException(PriceRevisionErrorCode.PRODUCT_NOT_FOUND));
            if (!Boolean.TRUE.equals(feature.getAddonAvailable())) {
                throw new BusinessException(PriceRevisionErrorCode.ADDON_NOT_AVAILABLE_FOR_REVISION);
            }
        }
    }

    /**
     * ACTIVE の revision のみ overlap 判定対象とする。{@code effectiveUntil} が null（open-ended）の
     * ACTIVE は、後続 future の activate 時に truncate される前提のため、この時点では衝突とみなさない
     * （AC-45）。{@code effectiveUntil} が既に設定済みの ACTIVE のみ半開区間で比較する（AC-46/48/49）。
     */
    private void assertNoActiveOverlap(
            List<BillingPriceVersionEntity> existing, Instant candidateFrom, Instant candidateUntil) {
        Instant candidateUntilOrMax = candidateUntil != null ? candidateUntil : Instant.MAX;
        for (BillingPriceVersionEntity e : existing) {
            if (e.getStatus() != BillingPriceVersionStatus.ACTIVE) {
                continue;
            }
            Instant existingUntil = e.getEffectiveUntil();
            if (existingUntil == null) {
                continue;
            }
            boolean overlap = e.getEffectiveFrom().isBefore(candidateUntilOrMax)
                    && candidateFrom.isBefore(existingUntil);
            if (overlap) {
                throw new BusinessException(PriceRevisionErrorCode.REVISION_OVERLAP);
            }
        }
    }
}
