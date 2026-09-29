package com.mannschaft.app.billing.api;

import com.mannschaft.app.auth.AuditEventType;
import com.mannschaft.app.auth.service.AuditLogService;
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

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * {@code POST /price-revisions/{id}/cancel}: 価格改定の取り消し（御裁可 2026-09-24）。
 *
 * <p>単一 future 制限（DRAFT/PROVISIONING/PROVISION_FAILED/READY/SCHEDULED は同一商品につき1本まで）に
 * PROVISION_FAILED を含めたことで、修復できない失敗（税コードの誤記など）を抱えた revision がその商品の
 * future 枠を永久に塞ぎうる。取り消しはその出口であり、revision と全 band を {@code CANCELLED} にして
 * future 枠（{@code uk_bpv_single_future}）を解放する。</p>
 *
 * <ul>
 *   <li>取り消せるのは DRAFT / READY / PROVISION_FAILED のみ。PROVISIONING（実行中）・SCHEDULED/ACTIVE/
 *       RETIRED（販売に関与済み）・CANCELLED は409 {@code STATE_CONFLICT}。</li>
 *   <li>{@code lockVersion} を必須の CAS 条件とする（不一致は409）。</li>
 *   <li>Stripe 側で作成済みの Price には触らない（archive しない）。陣立て書・設計書に archive の記述が無く、
 *       取り消した revision の Price は販売経路（{@code BillingPriceSelector}）に乗らないため。</li>
 * </ul>
 */
@Service
public class PriceRevisionCancelService {

    /** 取り消しを許す revision の状態。 */
    private static final Set<BillingPriceVersionStatus> CANCELLABLE_STATUSES = EnumSet.of(
            BillingPriceVersionStatus.DRAFT, BillingPriceVersionStatus.READY,
            BillingPriceVersionStatus.PROVISION_FAILED);

    private final BillingPriceVersionRepository versionRepository;
    private final BillingPriceBandVersionRepository bandRepository;
    private final AuditLogService auditLogService;

    public PriceRevisionCancelService(
            BillingPriceVersionRepository versionRepository,
            BillingPriceBandVersionRepository bandRepository,
            AuditLogService auditLogService) {
        this.versionRepository = versionRepository;
        this.bandRepository = bandRepository;
        this.auditLogService = auditLogService;
    }

    @Transactional
    public PriceRevisionResponse cancel(UUID id, long lockVersion, Long actorId) {
        BillingPriceVersionEntity revision = versionRepository.findByIdAndDeletedAtIsNull(id)
                .orElseThrow(() -> new BusinessException(PriceRevisionErrorCode.REVISION_NOT_FOUND));
        if (revision.getLockVersion() == null || revision.getLockVersion() != lockVersion) {
            throw new BusinessException(PriceRevisionErrorCode.LOCK_VERSION_CONFLICT);
        }
        if (!CANCELLABLE_STATUSES.contains(revision.getStatus())) {
            throw new BusinessException(PriceRevisionErrorCode.STATE_CONFLICT);
        }

        List<BillingPriceBandVersionEntity> bands = bandRepository.findAllByPriceVersionIdForUpdate(id);
        for (BillingPriceBandVersionEntity band : bands) {
            band.setStatus(BillingPriceVersionStatus.CANCELLED);
        }
        bandRepository.saveAll(bands);

        revision.setStatus(BillingPriceVersionStatus.CANCELLED);
        versionRepository.save(revision);
        // lockVersion は @Version で flush 時にのみ進むため、応答構築前に確定させる。
        versionRepository.flush();

        // 監査記録。Stripe Price/Product ID は載せない（AC-168 と同じ扱い）。
        auditLogService.record(AuditEventType.PRICE_REVISION_CANCELLED.name(), actorId, null, null, null,
                null, null, null,
                "{\"revisionId\":\"" + revision.getId() + "\",\"status\":\"" + revision.getStatus() + "\"}");

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
