package com.mannschaft.app.billing;

import com.mannschaft.app.billing.api.dto.PriceRevisionBandResponse;
import com.mannschaft.app.billing.api.dto.PriceRevisionResponse;
import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.payment.stripe.StripeEnvironmentIdentifier;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * 試練隊（第2陣）F群: {@code POST /price-revisions/{id}/reconcile-provision}（決定3改訂・決定9改訂）。
 *
 * <p>{@code BillingContractOperationRecoveryService} と同じドメインパッケージに置く回収専用サービス。
 * PROVISIONING のまま停止した revision/band を、Stripe 側の metadata 照合＋<b>全属性再照合</b>
 * （unit_amount/currency/recurring/Product/tax_behavior/Product の tax_code）で READY へ回収するか、
 * 一致しなければ {@code RECONCILE_ATTRIBUTE_MISMATCH} で隔離する（第5版・重大3の直接反証）。</p>
 *
 * <p>正本: {@code .claude/campaigns/price-rev-plan-v3.md} 決定3改訂・決定9改訂・AC-96〜AC-103。</p>
 */
@Service
public class BillingPriceProvisionRecoveryService {

    /** 決定9改訂（第6版・重大3対応）: provision系3EPと同一の9分猶予。 */
    private static final Duration STALE_THRESHOLD = Duration.ofMinutes(9);

    private final BillingPriceVersionRepository versionRepository;
    private final BillingPriceBandVersionRepository bandRepository;
    private final BillingPriceProvisionGateway gateway;
    private final Clock clock;
    private final StripeEnvironmentIdentifier environmentIdentifier;

    public BillingPriceProvisionRecoveryService(
            BillingPriceVersionRepository versionRepository,
            BillingPriceBandVersionRepository bandRepository,
            BillingPriceProvisionGateway gateway,
            Clock clock,
            StripeEnvironmentIdentifier environmentIdentifier) {
        this.versionRepository = versionRepository;
        this.bandRepository = bandRepository;
        this.gateway = gateway;
        this.clock = clock;
        this.environmentIdentifier = environmentIdentifier;
    }

    /** AC-99/AC-100: provision系3EPと同一の9分lease猶予。 */
    public Duration staleThreshold() {
        return STALE_THRESHOLD;
    }

    @Transactional
    public PriceRevisionResponse reconcileProvision(UUID id, long lockVersion) {
        BillingPriceVersionEntity revision = versionRepository.findByIdAndDeletedAtIsNull(id)
                .orElseThrow(() -> new BusinessException(PriceRevisionErrorCode.REVISION_NOT_FOUND));

        // AC-103: lockVersion を必須CAS条件とする。
        if (!Objects.equals(revision.getLockVersion(), lockVersion)) {
            throw new BusinessException(PriceRevisionErrorCode.LOCK_VERSION_CONFLICT);
        }

        List<BillingPriceBandVersionEntity> bands = bandRepository.findAllByPriceVersionIdForUpdate(id);

        boolean allReady = true;
        String firstErrorCode = null;
        for (BillingPriceBandVersionEntity band : bands) {
            if (band.getStatus() != BillingPriceVersionStatus.PROVISIONING) {
                if (band.getStatus() != BillingPriceVersionStatus.READY) {
                    allReady = false;
                }
                continue;
            }

            String errorCode = reconcileBand(revision, band);
            if (errorCode != null) {
                allReady = false;
                if (firstErrorCode == null) {
                    firstErrorCode = errorCode;
                }
            }
        }
        bandRepository.saveAll(bands);

        revision.setStatus(allReady ? BillingPriceVersionStatus.READY : BillingPriceVersionStatus.PROVISION_FAILED);
        revision.setLastProvisionErrorCode(allReady ? null : firstErrorCode);
        versionRepository.save(revision);
        // 根治治療（2026-09-23）: lockVersion は @Version（JPA optimistic lock）で Hibernate が
        // flush 時にのみインクリメントする。save() 直後に revision.getLockVersion() を読んでも
        // flush 前は旧値のままのことがあり、レスポンスの lockVersion が実際に永続化された値と
        // 食い違う（クライアントが次のCAS呼び出しで確実に409になる）。明示的に flush して
        // レスポンス構築前に確定させる。
        versionRepository.flush();

        return toResponse(revision, bands);
    }

    /**
     * AC-96/AC-97/AC-97a/AC-98: metadata で見つけた Price の全属性（tax_code含む）が band snapshot と
     * 一致する場合にのみ READY へ回収する。
     */
    private String reconcileBand(BillingPriceVersionEntity revision, BillingPriceBandVersionEntity band) {
        Optional<BillingPriceProvisionGateway.PriceSnapshot> found =
                gateway.findPriceByMetadata(revision.getId(), band.getId());
        if (found.isEmpty()) {
            band.setStatus(BillingPriceVersionStatus.PROVISION_FAILED);
            String code = "RECONCILE_PRICE_NOT_FOUND";
            band.setProvisionErrorCode(code);
            return code;
        }

        BillingPriceProvisionGateway.PriceSnapshot snapshot = found.get();
        boolean matches = snapshot.unitAmount() == band.getInputAmount()
                && "jpy".equalsIgnoreCase(snapshot.currency())
                && "month".equalsIgnoreCase(snapshot.recurringInterval())
                && snapshot.recurringIntervalCount() == 1
                && band.getProductKind().name().equals(snapshot.productKind())
                && band.getProductKey().equals(snapshot.productKey())
                && band.getTaxBehavior().name().equalsIgnoreCase(snapshot.taxBehavior())
                // AC-97a（第5版・重大3の直接反証）: Product 実体の tax_code まで一致しなければ回収しない。
                && Objects.equals(band.getTaxCodeSnapshot(), snapshot.productTaxCode())
                // AC-79: test/live Price 分離。作成時に焼いた環境識別子と現在の実行環境の識別子が
                // 一致しなければ回収しない（test 環境で作られた Price を live 環境が拾う事故を防ぐ）。
                // "unknown" 同士は素直な等値比較で一致扱いになる（ローカル開発は両側とも
                // unknown になるため自然に通る。特別扱いのコードは書かない）。
                && Objects.equals(environmentIdentifier.environmentId(), snapshot.environmentId());

        if (!matches) {
            band.setStatus(BillingPriceVersionStatus.PROVISION_FAILED);
            String code = PriceRevisionErrorCode.RECONCILE_ATTRIBUTE_MISMATCH.name();
            band.setProvisionErrorCode(code);
            return code;
        }

        band.setStripePriceRef(snapshot.stripePriceId());
        band.setStatus(BillingPriceVersionStatus.READY);
        band.setProvisionErrorCode(null);
        return null;
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
