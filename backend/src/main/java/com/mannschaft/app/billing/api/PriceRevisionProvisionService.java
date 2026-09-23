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
import com.mannschaft.app.auth.AuditEventType;
import com.mannschaft.app.auth.service.AuditLogService;
import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.payment.stripe.StripeEnvironmentIdentifier;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * 試練隊（第2陣）E群: {@code POST /price-revisions/{id}/provision}（決定2・決定9改訂）。
 *
 * <p>同期実行で常に200＋終局状態（READY/PROVISION_FAILED）を返す。202は返さない。
 * DB へ band ごとの PROVISIONING を commit した後にのみ Stripe を呼ぶ（AC-68）。
 * fail-forward: 1 band の失敗で以降を中断しない（AC-72）。</p>
 *
 * <p>正本: {@code .claude/campaigns/price-rev-plan-v3.md} 決定2・決定9改訂・AC-66〜AC-88d。</p>
 */
@Service
public class PriceRevisionProvisionService {

    private final BillingPriceVersionRepository versionRepository;
    private final BillingPriceBandVersionRepository bandRepository;
    private final BillingStripeProductRepository stripeProductRepository;
    private final BillingPriceProvisionGateway gateway;
    private final Clock clock;
    private final StripeEnvironmentIdentifier environmentIdentifier;
    private final AuditLogService auditLogService;

    public PriceRevisionProvisionService(
            BillingPriceVersionRepository versionRepository,
            BillingPriceBandVersionRepository bandRepository,
            BillingStripeProductRepository stripeProductRepository,
            BillingPriceProvisionGateway gateway,
            Clock clock,
            StripeEnvironmentIdentifier environmentIdentifier,
            AuditLogService auditLogService) {
        this.versionRepository = versionRepository;
        this.bandRepository = bandRepository;
        this.stripeProductRepository = stripeProductRepository;
        this.gateway = gateway;
        this.clock = clock;
        this.environmentIdentifier = environmentIdentifier;
        this.auditLogService = auditLogService;
    }

    @Transactional
    public PriceRevisionResponse provision(UUID id, long lockVersion, Long actorId) {
        BillingPriceVersionEntity revision = versionRepository.findByIdAndDeletedAtIsNull(id)
                .orElseThrow(() -> new BusinessException(PriceRevisionErrorCode.REVISION_NOT_FOUND));

        if (revision.getLockVersion() == null || revision.getLockVersion() != lockVersion) {
            throw new BusinessException(PriceRevisionErrorCode.LOCK_VERSION_CONFLICT);
        }
        // AC-77/AC-78: DRAFT からのみ provision できる（READY/SCHEDULED/ACTIVE/RETIRED/PROVISIONING は409）。
        if (revision.getStatus() != BillingPriceVersionStatus.DRAFT) {
            throw new BusinessException(PriceRevisionErrorCode.STATE_CONFLICT);
        }

        List<BillingPriceBandVersionEntity> bands = bandRepository.findAllByPriceVersionIdForUpdate(id);

        boolean allReady = true;
        String firstErrorCode = null;
        for (BillingPriceBandVersionEntity band : bands) {
            // AC-68: Stripe を呼ぶ前に必ず PROVISIONING を commit する。
            band.setStatus(BillingPriceVersionStatus.PROVISIONING);
            bandRepository.save(band);

            PriceRevisionProvisionSupport.attemptProvisionBand(
                    stripeProductRepository, gateway, revision.getId(),
                    revision.getProductKind(), revision.getProductKey(), band,
                    environmentIdentifier.environmentId());

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

        // L群AC-171: 監査記録。band成否の詳細・Stripe Price/Product IDは載せない（AC-168）。
        auditLogService.record(AuditEventType.PRICE_REVISION_PROVISIONED.name(), actorId, null, null, null,
                null, null, null,
                "{\"revisionId\":\"" + revision.getId() + "\",\"status\":\"" + revision.getStatus() + "\"}");

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
