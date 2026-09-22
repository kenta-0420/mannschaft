package com.mannschaft.app.billing.api;

import com.mannschaft.app.billing.BillingPriceBandVersionEntity;
import com.mannschaft.app.billing.BillingPriceBandVersionRepository;
import com.mannschaft.app.billing.BillingPriceProvisionGateway;
import com.mannschaft.app.billing.BillingPriceVersionEntity;
import com.mannschaft.app.billing.BillingPriceVersionRepository;
import com.mannschaft.app.billing.BillingPriceVersionStatus;
import com.mannschaft.app.billing.BillingStripeProductRepository;
import com.mannschaft.app.billing.PriceRevisionErrorCode;
import com.mannschaft.app.billing.api.dto.PriceRevisionBandResponse;
import com.mannschaft.app.billing.api.dto.PriceRevisionResponse;
import com.mannschaft.app.common.BusinessException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * 試練隊（第2陣）F群: {@code POST /price-revisions/{id}/retry-provision}（決定1・決定3改訂・決定9改訂）。
 *
 * <p>retry対象は {@code status IN (DRAFT, PROVISION_FAILED)} の全 band。READY の band の Price は
 * 作り直さない（AC-90）。PROVISIONING への retry は409（回収は reconcile 専用・AC-95）。</p>
 *
 * <p>正本: {@code .claude/campaigns/price-rev-plan-v3.md} 決定1・決定3改訂・決定9改訂・AC-89〜AC-95。</p>
 */
@Service
public class PriceRevisionRetryProvisionService {

    /** retry の対象となる band 状態（READY は作り直さない）。 */
    private static final Set<BillingPriceVersionStatus> RETRY_TARGET_BAND_STATUSES =
            EnumSet.of(BillingPriceVersionStatus.DRAFT, BillingPriceVersionStatus.PROVISION_FAILED);

    private final BillingPriceVersionRepository versionRepository;
    private final BillingPriceBandVersionRepository bandRepository;
    private final BillingStripeProductRepository stripeProductRepository;
    private final BillingPriceProvisionGateway gateway;
    private final Clock clock;

    public PriceRevisionRetryProvisionService(
            BillingPriceVersionRepository versionRepository,
            BillingPriceBandVersionRepository bandRepository,
            BillingStripeProductRepository stripeProductRepository,
            BillingPriceProvisionGateway gateway,
            Clock clock) {
        this.versionRepository = versionRepository;
        this.bandRepository = bandRepository;
        this.stripeProductRepository = stripeProductRepository;
        this.gateway = gateway;
        this.clock = clock;
    }

    @Transactional
    public PriceRevisionResponse retryProvision(UUID id, long lockVersion) {
        BillingPriceVersionEntity revision = versionRepository.findByIdAndDeletedAtIsNull(id)
                .orElseThrow(() -> new BusinessException(PriceRevisionErrorCode.REVISION_NOT_FOUND));

        if (revision.getLockVersion() == null || revision.getLockVersion() != lockVersion) {
            throw new BusinessException(PriceRevisionErrorCode.LOCK_VERSION_CONFLICT);
        }
        // AC-94/AC-95: READY/SCHEDULED/ACTIVE/RETIRED/PROVISIONING は409（PROVISIONINGの回収はreconcile専用）。
        if (revision.getStatus() != BillingPriceVersionStatus.PROVISION_FAILED) {
            throw new BusinessException(PriceRevisionErrorCode.STATE_CONFLICT);
        }

        List<BillingPriceBandVersionEntity> bands = bandRepository.findAllByPriceVersionIdForUpdate(id);

        boolean allReady = true;
        String firstErrorCode = null;
        for (BillingPriceBandVersionEntity band : bands) {
            if (!RETRY_TARGET_BAND_STATUSES.contains(band.getStatus())) {
                // AC-90: READY の band は触らない（Stripe呼び出しもDB更新もしない）。
                if (band.getStatus() != BillingPriceVersionStatus.READY) {
                    allReady = false;
                }
                continue;
            }

            band.setStatus(BillingPriceVersionStatus.PROVISIONING);
            bandRepository.save(band);

            PriceRevisionProvisionSupport.attemptProvisionBand(
                    stripeProductRepository, gateway, revision.getId(),
                    revision.getProductKind(), revision.getProductKey(), band);

            if (band.getStatus() != BillingPriceVersionStatus.READY) {
                allReady = false;
                if (firstErrorCode == null) {
                    firstErrorCode = band.getProvisionErrorCode();
                }
            }
        }
        bandRepository.saveAll(bands);

        revision.setStatus(allReady ? BillingPriceVersionStatus.READY : BillingPriceVersionStatus.PROVISION_FAILED);
        revision.setLastProvisionErrorCode(allReady ? null : firstErrorCode);
        revision.setProvisionAttempts(PriceRevisionProvisionSupport.nz(revision.getProvisionAttempts()) + 1);
        versionRepository.save(revision);
        // 根治治療（2026-09-23）: lockVersion は @Version（JPA optimistic lock）で Hibernate が
        // flush 時にのみインクリメントする。save() 直後に revision.getLockVersion() を読んでも
        // flush 前は旧値のままのことがあり、レスポンスの lockVersion が実際に永続化された値と
        // 食い違う（クライアントが次のCAS呼び出しで確実に409になる）。明示的に flush して
        // レスポンス構築前に確定させる。
        versionRepository.flush();

        return toResponse(revision, bands);
    }

    private PriceRevisionResponse toResponse(
            BillingPriceVersionEntity revision, List<BillingPriceBandVersionEntity> bands) {
        List<PriceRevisionBandResponse> bandResponses = new ArrayList<>();
        for (BillingPriceBandVersionEntity band : bands) {
            bandResponses.add(PriceRevisionProvisionSupport.toBandResponse(band));
        }
        return PriceRevisionResponse.builder()
                .id(revision.getId())
                .productKind(revision.getProductKind())
                .productKey(revision.getProductKey())
                .scopeKind(revision.getScopeKind())
                .revisionNo(revision.getRevisionNo())
                .catalogRevision(revision.getCatalogRevision())
                .status(revision.getStatus())
                .lockVersion(revision.getLockVersion())
                .effectiveFrom(revision.getEffectiveFrom())
                .effectiveUntil(revision.getEffectiveUntil())
                .bands(bandResponses)
                .build();
    }
}
