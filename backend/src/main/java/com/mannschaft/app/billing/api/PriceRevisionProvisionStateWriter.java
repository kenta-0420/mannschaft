package com.mannschaft.app.billing.api;

import com.mannschaft.app.auth.AuditEventType;
import com.mannschaft.app.auth.service.AuditLogService;
import com.mannschaft.app.billing.BillingPriceBandVersionEntity;
import com.mannschaft.app.billing.BillingPriceBandVersionRepository;
import com.mannschaft.app.billing.BillingPriceVersionEntity;
import com.mannschaft.app.billing.BillingPriceVersionRepository;
import com.mannschaft.app.billing.BillingPriceVersionStatus;
import com.mannschaft.app.billing.BillingProductKind;
import com.mannschaft.app.billing.BillingTaxBehavior;
import com.mannschaft.app.billing.PriceRevisionErrorCode;
import com.mannschaft.app.billing.api.dto.PriceRevisionBandResponse;
import com.mannschaft.app.billing.api.dto.PriceRevisionResponse;
import com.mannschaft.app.common.BusinessException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * provision / retry-provision の DB 状態遷移だけを担う Bean（AC-68・AC-69）。
 *
 * <p>Stripe 呼び出しを DB トランザクションの外に出すため、状態遷移を2つの独立トランザクションに分ける:</p>
 * <ol>
 *   <li>{@link #begin}: CAS・状態検査の後、revision と対象 band を {@code PROVISIONING} にして
 *       <b>commit する</b>（{@code REQUIRES_NEW}）。</li>
 *   <li>（呼び出し元がトランザクション外で Stripe を呼ぶ）</li>
 *   <li>{@link #complete}: band ごとの結果を反映し、revision を終局状態（READY/PROVISION_FAILED）にして
 *       commit する（{@code REQUIRES_NEW}）。</li>
 * </ol>
 *
 * <p>根治治療（2026-09-24・Codex 検分 P1）: 以前は provision 全体が1つの {@code @Transactional} だったため、
 * Stripe 呼び出し中にプロセスが死ぬと PROVISIONING がロールバックされて DRAFT に戻り、Stripe 側にだけ
 * Price が残って reconcile の回収対象（PROVISIONING）にならなかった。{@code begin} を commit してから
 * Stripe を呼ぶことで、途中停止しても PROVISIONING が DB に残り reconcile で回収できる。</p>
 *
 * <p>自己呼び出しでは AOP のトランザクションプロキシが効かないため、呼び出し元
 * （{@link PriceRevisionProvisionService} / {@link PriceRevisionRetryProvisionService}）とは別 Bean にしている。
 * 公開メソッドは Entity を引数・戻り値に持たない（D-1 API 境界）。</p>
 */
@Service
public class PriceRevisionProvisionStateWriter {

    private final BillingPriceVersionRepository versionRepository;
    private final BillingPriceBandVersionRepository bandRepository;
    private final AuditLogService auditLogService;

    public PriceRevisionProvisionStateWriter(
            BillingPriceVersionRepository versionRepository,
            BillingPriceBandVersionRepository bandRepository,
            AuditLogService auditLogService) {
        this.versionRepository = versionRepository;
        this.bandRepository = bandRepository;
        this.auditLogService = auditLogService;
    }

    /** Stripe 呼び出しの対象（begin の commit 時点の band snapshot）。 */
    public record ProvisionPlan(
            UUID revisionId, BillingProductKind productKind, String productKey, List<BandTarget> bands) {
    }

    /** Stripe へ渡す band 1件分の snapshot 値。 */
    public record BandTarget(
            UUID bandId, long inputAmount, BillingTaxBehavior taxBehavior, String taxMasterSnapshot) {
    }

    /** band 1件分の Stripe 呼び出し結果。{@code errorCode == null} なら成功。 */
    public record BandOutcome(UUID bandId, String stripePriceRef, String errorCode) {

        public boolean succeeded() {
            return errorCode == null;
        }
    }

    /**
     * CAS・状態検査の後、revision と {@code targetBandStatuses} に該当する band を PROVISIONING にして commit する。
     *
     * @param requiredRevisionStatus この状態の revision にのみ実行できる（それ以外は409）
     * @param targetBandStatuses     Stripe 呼び出しの対象にする band の状態
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public ProvisionPlan begin(UUID id, long lockVersion, BillingPriceVersionStatus requiredRevisionStatus,
            Set<BillingPriceVersionStatus> targetBandStatuses) {
        BillingPriceVersionEntity revision = versionRepository.findByIdAndDeletedAtIsNull(id)
                .orElseThrow(() -> new BusinessException(PriceRevisionErrorCode.REVISION_NOT_FOUND));

        if (revision.getLockVersion() == null || revision.getLockVersion() != lockVersion) {
            throw new BusinessException(PriceRevisionErrorCode.LOCK_VERSION_CONFLICT);
        }
        if (revision.getStatus() != requiredRevisionStatus) {
            throw new BusinessException(PriceRevisionErrorCode.STATE_CONFLICT);
        }

        List<BillingPriceBandVersionEntity> bands = bandRepository.findAllByPriceVersionIdForUpdate(id);
        List<BandTarget> targets = new ArrayList<>();
        for (BillingPriceBandVersionEntity band : bands) {
            if (!targetBandStatuses.contains(band.getStatus())) {
                continue;
            }
            band.setStatus(BillingPriceVersionStatus.PROVISIONING);
            targets.add(new BandTarget(band.getId(), band.getInputAmount(), band.getTaxBehavior(),
                    band.getTaxMasterSnapshot()));
        }
        bandRepository.saveAll(bands);

        // revision 自体も PROVISIONING にする（lockVersion が進むため、同じ lockVersion での
        // 並行 provision/retry は CAS で409になる。AC-78/AC-95 の「PROVISIONING は409」もここで成立する）。
        revision.setStatus(BillingPriceVersionStatus.PROVISIONING);
        versionRepository.save(revision);
        versionRepository.flush();

        return new ProvisionPlan(revision.getId(), revision.getProductKind(), revision.getProductKey(), targets);
    }

    /**
     * Stripe 呼び出しの結果を反映し、revision を終局状態にして commit する。
     *
     * <p>begin の後に reconcile 等で revision が PROVISIONING でなくなっていれば409（結果は反映しない。
     * Stripe 側の Price は metadata で後から回収できる）。</p>
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public PriceRevisionResponse complete(UUID id, List<BandOutcome> outcomes, Long actorId,
            AuditEventType auditEventType) {
        BillingPriceVersionEntity revision = versionRepository.findByIdAndDeletedAtIsNull(id)
                .orElseThrow(() -> new BusinessException(PriceRevisionErrorCode.REVISION_NOT_FOUND));
        if (revision.getStatus() != BillingPriceVersionStatus.PROVISIONING) {
            throw new BusinessException(PriceRevisionErrorCode.STATE_CONFLICT);
        }

        Map<UUID, BandOutcome> outcomeByBandId = new HashMap<>();
        for (BandOutcome outcome : outcomes) {
            outcomeByBandId.put(outcome.bandId(), outcome);
        }

        List<BillingPriceBandVersionEntity> bands = bandRepository.findAllByPriceVersionIdForUpdate(id);
        boolean allReady = true;
        String firstErrorCode = null;
        for (BillingPriceBandVersionEntity band : bands) {
            BandOutcome outcome = outcomeByBandId.get(band.getId());
            if (outcome != null && band.getStatus() == BillingPriceVersionStatus.PROVISIONING) {
                band.setProvisionAttempts(PriceRevisionProvisionSupport.nz(band.getProvisionAttempts()) + 1);
                if (outcome.succeeded()) {
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
        revision.setProvisionAttempts(PriceRevisionProvisionSupport.nz(revision.getProvisionAttempts()) + 1);
        versionRepository.save(revision);
        // lockVersion は @Version（JPA optimistic lock）で flush 時にのみ進む。応答の lockVersion を
        // 実際に永続化された値と一致させるため、応答構築前に明示的に flush する。
        versionRepository.flush();

        // L群AC-171: 監査記録。band成否の詳細・Stripe Price/Product IDは載せない（AC-168）。
        auditLogService.record(auditEventType.name(), actorId, null, null, null,
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
