package com.mannschaft.app.billing;

import com.mannschaft.app.auth.AuditEventType;
import com.mannschaft.app.auth.service.AuditLogService;
import com.mannschaft.app.billing.api.dto.PriceRevisionBandResponse;
import com.mannschaft.app.billing.api.dto.PriceRevisionResponse;
import com.mannschaft.app.common.BusinessException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * reconcile-provision の DB 側（読み取り・結果反映）だけを担う Bean（AC-69・AC-96〜AC-103）。
 *
 * <p>{@link BillingPriceProvisionRecoveryService} は Stripe へ Price を照会する。その間 DB トランザクション
 * （と band 行の {@code FOR UPDATE}）を開いたままにしないよう、DB 操作を2つの独立トランザクションに分ける:</p>
 * <ol>
 *   <li>{@link #begin}: CAS の後、回収対象（PROVISIONING の band）の snapshot 値を読んで commit する。</li>
 *   <li>（呼び出し元がトランザクション外で Stripe を照会し、全属性を照合する）</li>
 *   <li>{@link #complete}: begin 時点の lockVersion のままであることを確かめてから（楽観ロック）、
 *       照合結果を反映し revision を終局状態にして commit する。</li>
 * </ol>
 *
 * <p>根治治療（2026-09-24）: 以前は reconcile 全体が1つの {@code @Transactional} で、band 行を握ったまま
 * Stripe の応答を待っていた（provision の同型欠陥の兄弟経路）。自己呼び出しでは AOP プロキシが効かない
 * ため別 Bean にしている。公開メソッドは Entity を引数・戻り値に持たない（D-1 API 境界）。</p>
 */
@Service
public class BillingPriceReconcileStateWriter {

    /** 1回の reconcile で回収する band の上限（revision の band 上限と同じ10）。AC-99 の件数上限。 */
    static final int MAX_RECONCILE_BANDS = 10;

    private final BillingPriceVersionRepository versionRepository;
    private final BillingPriceBandVersionRepository bandRepository;
    private final AuditLogService auditLogService;

    public BillingPriceReconcileStateWriter(
            BillingPriceVersionRepository versionRepository,
            BillingPriceBandVersionRepository bandRepository,
            AuditLogService auditLogService) {
        this.versionRepository = versionRepository;
        this.bandRepository = bandRepository;
        this.auditLogService = auditLogService;
    }

    /** 回収の対象（begin の commit 時点の snapshot）。 */
    public record ReconcilePlan(UUID revisionId, long lockVersion, List<ReconcileTarget> bands) {
    }

    /** 全属性照合に使う band 1件分の snapshot 値。 */
    public record ReconcileTarget(UUID bandId, BillingProductKind productKind, String productKey,
            long inputAmount, BillingTaxBehavior taxBehavior, String taxMasterSnapshot) {
    }

    /** band 1件分の照合結果。{@code errorCode == null} なら READY へ回収する。 */
    public record ReconcileOutcome(UUID bandId, String stripePriceRef, String errorCode) {
    }

    /**
     * CAS（AC-103）と状態検査の後、停滞した PROVISIONING の band を回収対象として返す（DB は変更しない）。
     *
     * <ul>
     *   <li>revision が PROVISIONING 以外（DRAFT/PROVISION_FAILED/READY/SCHEDULED/ACTIVE/RETIRED 等）は409
     *       {@code STATE_CONFLICT}。PROVISION_FAILED の再実行は retry-provision の責務（AC-95 の対）。</li>
     *   <li>PROVISIONING の band のうち1件でも {@code updated_at >= staleBefore}（＝staleThreshold 未満）なら、
     *       進行中の provision/retry とみなし409 {@code PROVISION_IN_PROGRESS}（AC-100: 横取りしない）。</li>
     *   <li>回収対象は古い順（updated_at 昇順）・最大 {@link #MAX_RECONCILE_BANDS} 件（AC-99）。</li>
     * </ul>
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public ReconcilePlan begin(UUID id, long lockVersion, Instant staleBefore) {
        BillingPriceVersionEntity revision = versionRepository.findByIdAndDeletedAtIsNull(id)
                .orElseThrow(() -> new BusinessException(PriceRevisionErrorCode.REVISION_NOT_FOUND));
        if (!Objects.equals(revision.getLockVersion(), lockVersion)) {
            throw new BusinessException(PriceRevisionErrorCode.LOCK_VERSION_CONFLICT);
        }
        if (revision.getStatus() != BillingPriceVersionStatus.PROVISIONING) {
            throw new BusinessException(PriceRevisionErrorCode.STATE_CONFLICT);
        }

        List<BillingPriceBandVersionEntity> provisioning = new ArrayList<>();
        for (BillingPriceBandVersionEntity band : bandRepository.findAllByPriceVersionIdForUpdate(id)) {
            if (band.getStatus() == BillingPriceVersionStatus.PROVISIONING) {
                if (band.getUpdatedAt() == null || !band.getUpdatedAt().isBefore(staleBefore)) {
                    throw new BusinessException(PriceRevisionErrorCode.PROVISION_IN_PROGRESS);
                }
                provisioning.add(band);
            }
        }
        provisioning.sort(Comparator.comparing(BillingPriceBandVersionEntity::getUpdatedAt));

        List<ReconcileTarget> targets = new ArrayList<>();
        for (BillingPriceBandVersionEntity band : provisioning.subList(0,
                Math.min(provisioning.size(), MAX_RECONCILE_BANDS))) {
            targets.add(new ReconcileTarget(band.getId(), band.getProductKind(), band.getProductKey(),
                    band.getInputAmount(), band.getTaxBehavior(), band.getTaxMasterSnapshot()));
        }
        return new ReconcilePlan(revision.getId(), lockVersion, targets);
    }

    /**
     * 照合結果を反映し revision を終局状態にして commit する。begin の後に revision が更新されていれば
     * （lockVersion が進んでいれば）409 とし、結果は反映しない。
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public PriceRevisionResponse complete(UUID id, long expectedLockVersion, List<ReconcileOutcome> outcomes,
            Long actorId) {
        BillingPriceVersionEntity revision = versionRepository.findByIdAndDeletedAtIsNull(id)
                .orElseThrow(() -> new BusinessException(PriceRevisionErrorCode.REVISION_NOT_FOUND));
        if (!Objects.equals(revision.getLockVersion(), expectedLockVersion)) {
            throw new BusinessException(PriceRevisionErrorCode.LOCK_VERSION_CONFLICT);
        }
        if (revision.getStatus() != BillingPriceVersionStatus.PROVISIONING) {
            throw new BusinessException(PriceRevisionErrorCode.STATE_CONFLICT);
        }

        Map<UUID, ReconcileOutcome> outcomeByBandId = new HashMap<>();
        for (ReconcileOutcome outcome : outcomes) {
            outcomeByBandId.put(outcome.bandId(), outcome);
        }

        List<BillingPriceBandVersionEntity> bands = bandRepository.findAllByPriceVersionIdForUpdate(id);
        boolean allReady = true;
        String firstErrorCode = null;
        for (BillingPriceBandVersionEntity band : bands) {
            ReconcileOutcome outcome = outcomeByBandId.get(band.getId());
            if (outcome != null && band.getStatus() == BillingPriceVersionStatus.PROVISIONING) {
                if (outcome.errorCode() == null) {
                    band.setStripePriceRef(outcome.stripePriceRef());
                    band.setStatus(BillingPriceVersionStatus.READY);
                    band.setProvisionErrorCode(null);
                } else {
                    band.setStatus(BillingPriceVersionStatus.PROVISION_FAILED);
                    band.setProvisionErrorCode(outcome.errorCode());
                    if (firstErrorCode == null) {
                        firstErrorCode = outcome.errorCode();
                    }
                }
            }
            if (band.getStatus() != BillingPriceVersionStatus.READY) {
                allReady = false;
            }
        }
        bandRepository.saveAll(bands);

        revision.setStatus(allReady ? BillingPriceVersionStatus.READY : BillingPriceVersionStatus.PROVISION_FAILED);
        revision.setLastProvisionErrorCode(allReady ? null : firstErrorCode);
        versionRepository.save(revision);
        // lockVersion は @Version（JPA optimistic lock）で flush 時にのみ進む。応答の lockVersion を
        // 実際に永続化された値と一致させるため、応答構築前に明示的に flush する。
        versionRepository.flush();

        // L群AC-171: 監査記録。band成否の詳細・Stripe Price/Product IDは載せない（AC-168）。
        auditLogService.record(AuditEventType.PRICE_REVISION_RECONCILED.name(), actorId, null, null, null,
                null, null, null,
                "{\"revisionId\":\"" + revision.getId() + "\",\"status\":\"" + revision.getStatus() + "\"}");

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
                .lockVersion(revision.getLockVersion())
                .effectiveFrom(revision.getEffectiveFrom())
                .effectiveUntil(revision.getEffectiveUntil())
                .bands(bandResponses)
                .build();
    }
}
