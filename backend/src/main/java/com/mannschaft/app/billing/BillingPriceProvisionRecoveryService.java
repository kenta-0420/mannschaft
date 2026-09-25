package com.mannschaft.app.billing;

import com.mannschaft.app.billing.api.dto.PriceRevisionResponse;
import com.mannschaft.app.billing.tax.BillingTaxMasterSnapshot;
import com.mannschaft.app.payment.stripe.StripeEnvironmentIdentifier;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
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
 * <p>本メソッド自体は {@code @Transactional} にしない（AC-69: Stripe 呼び出しを DB トランザクションに
 * 含めない）。DB の読み取りと結果反映は別 Bean {@link BillingPriceReconcileStateWriter} の2つの独立
 * トランザクションで行い、その間でトランザクション外に Stripe を照会する。</p>
 *
 * <p>正本: {@code .claude/campaigns/price-rev-plan-v3.md} 決定3改訂・決定9改訂・AC-96〜AC-103。</p>
 */
@Service
public class BillingPriceProvisionRecoveryService {

    /** 決定9改訂（第6版・重大3対応）: provision系3EPと同一の9分猶予。 */
    private static final Duration STALE_THRESHOLD = Duration.ofMinutes(9);

    private final BillingPriceReconcileStateWriter stateWriter;
    private final BillingPriceProvisionGateway gateway;
    private final StripeEnvironmentIdentifier environmentIdentifier;
    private final Clock clock;

    public BillingPriceProvisionRecoveryService(
            BillingPriceReconcileStateWriter stateWriter,
            BillingPriceProvisionGateway gateway,
            StripeEnvironmentIdentifier environmentIdentifier,
            Clock clock) {
        this.stateWriter = stateWriter;
        this.gateway = gateway;
        this.environmentIdentifier = environmentIdentifier;
        this.clock = clock;
    }

    /** AC-99/AC-100: provision系3EPと同一の9分lease猶予。 */
    public Duration staleThreshold() {
        return STALE_THRESHOLD;
    }

    public PriceRevisionResponse reconcileProvision(UUID id, long lockVersion, Long actorId) {
        // AC-102/AC-103: 404 と lockVersion の CAS。AC-99/AC-100: 注入された Clock を基準に、
        // PROVISIONING に入ってから staleThreshold（9分）以上経過した band だけを回収する
        // （それ未満は進行中の provision とみなし 409 PROVISION_IN_PROGRESS で横取りしない）。
        Instant staleBefore = clock.instant().minus(STALE_THRESHOLD);
        BillingPriceReconcileStateWriter.ReconcilePlan plan = stateWriter.begin(id, lockVersion, staleBefore);

        List<BillingPriceReconcileStateWriter.ReconcileOutcome> outcomes = new ArrayList<>();
        for (BillingPriceReconcileStateWriter.ReconcileTarget band : plan.bands()) {
            outcomes.add(reconcileBand(plan.revisionId(), band));
        }

        return stateWriter.complete(id, plan.lockVersion(), outcomes, actorId);
    }

    /**
     * AC-96/AC-97/AC-97a/AC-98: metadata で見つけた Price の全属性（tax_code含む）が band snapshot と
     * 一致する場合にのみ READY へ回収する（トランザクション外で呼ぶ）。
     */
    private BillingPriceReconcileStateWriter.ReconcileOutcome reconcileBand(
            UUID revisionId, BillingPriceReconcileStateWriter.ReconcileTarget band) {
        // 旧形式の税 snapshot は Product の tax_code と照合できない（未設定と区別できない）ため fail-closed。
        if (BillingTaxMasterSnapshot.isLegacyFormat(band.taxMasterSnapshot())) {
            return new BillingPriceReconcileStateWriter.ReconcileOutcome(
                    band.bandId(), null, PriceRevisionErrorCode.TAX_SNAPSHOT_LEGACY_FORMAT.name());
        }
        Optional<BillingPriceProvisionGateway.PriceSnapshot> found =
                gateway.findPriceByMetadata(revisionId, band.bandId());
        if (found.isEmpty()) {
            return new BillingPriceReconcileStateWriter.ReconcileOutcome(
                    band.bandId(), null, "RECONCILE_PRICE_NOT_FOUND");
        }

        BillingPriceProvisionGateway.PriceSnapshot snapshot = found.get();
        if (!BillingPriceSnapshotMatcher.matches(snapshot, band.productKind(), band.productKey(),
                band.inputAmount(), band.taxBehavior(), band.taxMasterSnapshot(),
                environmentIdentifier.environmentId())) {
            return new BillingPriceReconcileStateWriter.ReconcileOutcome(
                    band.bandId(), null, PriceRevisionErrorCode.RECONCILE_ATTRIBUTE_MISMATCH.name());
        }
        return new BillingPriceReconcileStateWriter.ReconcileOutcome(band.bandId(), snapshot.stripePriceId(), null);
    }
}
