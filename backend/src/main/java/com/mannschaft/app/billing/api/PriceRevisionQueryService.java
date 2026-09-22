package com.mannschaft.app.billing.api;

import com.mannschaft.app.billing.BillingPriceBandVersionEntity;
import com.mannschaft.app.billing.BillingPriceBandVersionRepository;
import com.mannschaft.app.billing.BillingPriceVersionEntity;
import com.mannschaft.app.billing.BillingPriceVersionRepository;
import com.mannschaft.app.billing.PriceRevisionErrorCode;
import com.mannschaft.app.billing.api.dto.PriceRevisionBandResponse;
import com.mannschaft.app.billing.api.dto.PriceRevisionListQuery;
import com.mannschaft.app.billing.api.dto.PriceRevisionPageResponse;
import com.mannschaft.app.billing.api.dto.PriceRevisionResponse;
import com.mannschaft.app.billing.api.dto.PriceRevisionSummaryResponse;
import com.mannschaft.app.common.BusinessException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * 出陣隊（第3陣）D群: {@code GET /price-revisions/{id}} / {@code GET /price-revisions}。
 *
 * <p>正本: {@code .claude/campaigns/price-rev-plan-v3.md} D群・AC-54〜AC-65。
 * 発注テストは {@link com.mannschaft.app.billing.api.PriceRevisionQueryServiceTest}。</p>
 */
@Service
public class PriceRevisionQueryService {

    private final BillingPriceVersionRepository versionRepository;
    private final BillingPriceBandVersionRepository bandRepository;

    public PriceRevisionQueryService(
            BillingPriceVersionRepository versionRepository, BillingPriceBandVersionRepository bandRepository) {
        this.versionRepository = versionRepository;
        this.bandRepository = bandRepository;
    }

    @Transactional(readOnly = true)
    public PriceRevisionResponse getById(UUID id) {
        // AC-55/AC-56: 論理削除を回避しない findByIdAndDeletedAtIsNull のみを使う。
        BillingPriceVersionEntity revision = versionRepository.findByIdAndDeletedAtIsNull(id)
                .orElseThrow(() -> new BusinessException(PriceRevisionErrorCode.REVISION_NOT_FOUND));

        // AC-57: 親1回＋子1回のクエリで完結する（N+1回避）。
        List<BillingPriceBandVersionEntity> bands =
                bandRepository.findByPriceVersionIdAndDeletedAtIsNullOrderByBandNoAsc(id);

        return toResponse(revision, bands);
    }

    @Transactional(readOnly = true)
    public PriceRevisionPageResponse list(PriceRevisionListQuery query) {
        List<BillingPriceVersionEntity> revisions = versionRepository.searchSummaries(query);
        List<PriceRevisionSummaryResponse> items = new ArrayList<>();
        for (BillingPriceVersionEntity revision : revisions) {
            items.add(new PriceRevisionSummaryResponse(
                    revision.getId(),
                    revision.getProductKind(),
                    revision.getProductKey(),
                    revision.getScopeKind(),
                    revision.getRevisionNo(),
                    revision.getStatus(),
                    revision.getEffectiveFrom(),
                    revision.getEffectiveUntil()));
        }
        return PriceRevisionPageResponse.builder()
                .items(items)
                .totalElements(items.size())
                .build();
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
