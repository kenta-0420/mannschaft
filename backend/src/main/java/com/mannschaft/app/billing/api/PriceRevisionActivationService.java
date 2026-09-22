package com.mannschaft.app.billing.api;

import com.mannschaft.app.billing.BillingPriceBandVersionEntity;
import com.mannschaft.app.billing.BillingPriceBandVersionRepository;
import com.mannschaft.app.billing.BillingPriceVersionEntity;
import com.mannschaft.app.billing.BillingPriceVersionRepository;
import com.mannschaft.app.billing.BillingPriceVersionStatus;
import com.mannschaft.app.billing.PriceRevisionErrorCode;
import com.mannschaft.app.billing.api.dto.PriceRevisionBandResponse;
import com.mannschaft.app.billing.api.dto.PriceRevisionResponse;
import com.mannschaft.app.common.BusinessException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * 試練隊（第2陣）G群: {@code POST /price-revisions/{id}/activate}（第6版・future 単一制限で全面改訂）。
 *
 * <p>全 band READY のときだけ activate が成功する（AC-105）。即時 activate は本サービス自身が
 * 同一トランザクションで旧 ACTIVE → RETIRED・対象 → ACTIVE を直接書き込む。
 * {@code BillingPricePromotionService.promoteDue} は一切呼ばない（決定4・AC-108/AC-117。
 * 依存を持たないことでコンストラクタレベルで固定する）。</p>
 *
 * <p>正本: {@code .claude/campaigns/price-rev-plan-v3.md} 決定4・AC-105〜AC-110・AC-114〜AC-119・AC-108a。</p>
 */
@Service
public class PriceRevisionActivationService {

    private final BillingPriceVersionRepository versionRepository;
    private final BillingPriceBandVersionRepository bandRepository;
    private final Clock clock;

    public PriceRevisionActivationService(
            BillingPriceVersionRepository versionRepository,
            BillingPriceBandVersionRepository bandRepository,
            Clock clock) {
        this.versionRepository = versionRepository;
        this.bandRepository = bandRepository;
        this.clock = clock;
    }

    @Transactional
    public PriceRevisionResponse activate(UUID id, long lockVersion) {
        BillingPriceVersionEntity revision = versionRepository.findByIdAndDeletedAtIsNull(id)
                .orElseThrow(() -> new BusinessException(PriceRevisionErrorCode.REVISION_NOT_FOUND));

        // AC-115: lockVersion CAS。
        if (revision.getLockVersion() == null || revision.getLockVersion() != lockVersion) {
            throw new BusinessException(PriceRevisionErrorCode.LOCK_VERSION_CONFLICT);
        }
        // AC-106/AC-116: READY 以外（DRAFT/PROVISIONING/PROVISION_FAILED/SCHEDULED/ACTIVE/RETIRED）は409。
        if (revision.getStatus() != BillingPriceVersionStatus.READY) {
            throw new BusinessException(PriceRevisionErrorCode.STATE_CONFLICT);
        }

        List<BillingPriceBandVersionEntity> bands = bandRepository.findAllByPriceVersionIdForUpdate(id);
        // AC-105: 1件でもREADYでなければ409（bandが1件も無い場合も不正として拒否する）。
        boolean allReady = !bands.isEmpty()
                && bands.stream().allMatch(band -> band.getStatus() == BillingPriceVersionStatus.READY);
        if (!allReady) {
            throw new BusinessException(PriceRevisionErrorCode.STATE_CONFLICT);
        }

        Instant now = clock.instant();
        boolean immediate = !revision.getEffectiveFrom().isAfter(now);

        List<BillingPriceVersionEntity> siblings = versionRepository.findAllForUpdate(
                revision.getProductKind(), revision.getProductKey(), revision.getScopeKind());
        BillingPriceVersionEntity previousActive = siblings.stream()
                .filter(sibling -> sibling.getStatus() == BillingPriceVersionStatus.ACTIVE
                        && !sibling.getId().equals(revision.getId()))
                .findFirst()
                .orElse(null);

        if (previousActive != null) {
            // AC-109: SCHEDULED化のみでも、旧ACTIVEのeffectiveUntilは同一Tx内でB.effectiveFromに揃える。
            previousActive.setEffectiveUntil(revision.getEffectiveFrom());
            List<BillingPriceBandVersionEntity> previousActiveBands =
                    bandRepository.findAllByPriceVersionIdForUpdate(previousActive.getId());
            for (BillingPriceBandVersionEntity previousBand : previousActiveBands) {
                previousBand.setEffectiveUntil(revision.getEffectiveFrom());
                if (immediate) {
                    // AC-108/AC-108a: 即時activateは旧ACTIVEをRETIREDへ直接書き込む（promoteDueは呼ばない）。
                    previousBand.setStatus(BillingPriceVersionStatus.RETIRED);
                }
            }
            if (immediate) {
                previousActive.setStatus(BillingPriceVersionStatus.RETIRED);
            }
            versionRepository.save(previousActive);
            bandRepository.saveAll(previousActiveBands);
        }

        BillingPriceVersionStatus targetStatus =
                immediate ? BillingPriceVersionStatus.ACTIVE : BillingPriceVersionStatus.SCHEDULED;
        revision.setStatus(targetStatus);
        for (BillingPriceBandVersionEntity band : bands) {
            band.setStatus(targetStatus);
        }
        bandRepository.saveAll(bands);
        versionRepository.save(revision);

        return toResponse(revision, bands);
    }

    private PriceRevisionResponse toResponse(
            BillingPriceVersionEntity revision, List<BillingPriceBandVersionEntity> bands) {
        List<PriceRevisionBandResponse> bandResponses = new ArrayList<>();
        for (BillingPriceBandVersionEntity band : bands) {
            bandResponses.add(PriceRevisionBandResponse.builder()
                    .id(band.getId())
                    .bandNo(band.getBandNo())
                    .minMembers(band.getMinMembers())
                    .maxMembers(band.getMaxMembers())
                    .inputAmount(band.getInputAmount())
                    .taxBehavior(band.getTaxBehavior())
                    .taxCode(band.getTaxCodeSnapshot())
                    .amountExcludingTax(band.getAmountExcludingTax())
                    .taxAmount(band.getTaxAmount())
                    .amountIncludingTax(band.getAmountIncludingTax())
                    .taxRateBasisPoints(band.getTaxRateBasisPoints())
                    .status(band.getStatus())
                    .stripePriceRef(band.getStripePriceRef())
                    .provisionErrorCode(band.getProvisionErrorCode())
                    .provisionAttempts(band.getProvisionAttempts() == null ? 0 : band.getProvisionAttempts())
                    .build());
        }
        return PriceRevisionResponse.builder()
                .id(revision.getId())
                .productKind(revision.getProductKind())
                .productKey(revision.getProductKey())
                .scopeKind(revision.getScopeKind())
                .revisionNo(revision.getRevisionNo())
                .catalogRevision(revision.getCatalogRevision())
                .status(revision.getStatus())
                .effectiveFrom(revision.getEffectiveFrom())
                .effectiveUntil(revision.getEffectiveUntil())
                .bands(bandResponses)
                .build();
    }
}
