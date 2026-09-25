package com.mannschaft.app.billing.api;

import com.mannschaft.app.auth.AuditEventType;
import com.mannschaft.app.billing.BillingPriceProvisionGateway;
import com.mannschaft.app.billing.BillingPriceVersionStatus;
import com.mannschaft.app.billing.BillingStripeProductRepository;
import com.mannschaft.app.billing.api.dto.PriceRevisionResponse;
import com.mannschaft.app.payment.stripe.StripeEnvironmentIdentifier;
import org.springframework.stereotype.Service;

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
 * <p>provision と同じく、PROVISIONING を別トランザクションで commit してからトランザクション外で
 * Stripe を呼ぶ（{@link PriceRevisionProvisionStateWriter}・AC-68/AC-69）。</p>
 *
 * <p>正本: {@code .claude/campaigns/price-rev-plan-v3.md} 決定1・決定3改訂・決定9改訂・AC-89〜AC-95。</p>
 */
@Service
public class PriceRevisionRetryProvisionService {

    /** retry の対象となる band 状態（READY は作り直さない）。 */
    private static final Set<BillingPriceVersionStatus> RETRY_TARGET_BAND_STATUSES =
            EnumSet.of(BillingPriceVersionStatus.DRAFT, BillingPriceVersionStatus.PROVISION_FAILED);

    private final PriceRevisionProvisionStateWriter stateWriter;
    private final BillingStripeProductRepository stripeProductRepository;
    private final BillingPriceProvisionGateway gateway;
    private final StripeEnvironmentIdentifier environmentIdentifier;

    public PriceRevisionRetryProvisionService(
            PriceRevisionProvisionStateWriter stateWriter,
            BillingStripeProductRepository stripeProductRepository,
            BillingPriceProvisionGateway gateway,
            StripeEnvironmentIdentifier environmentIdentifier) {
        this.stateWriter = stateWriter;
        this.stripeProductRepository = stripeProductRepository;
        this.gateway = gateway;
        this.environmentIdentifier = environmentIdentifier;
    }

    public PriceRevisionResponse retryProvision(UUID id, long lockVersion, Long actorId) {
        // AC-94/AC-95: PROVISION_FAILED 以外（READY/SCHEDULED/ACTIVE/RETIRED/PROVISIONING）は409。
        PriceRevisionProvisionStateWriter.ProvisionPlan plan = stateWriter.begin(
                id, lockVersion, BillingPriceVersionStatus.PROVISION_FAILED, RETRY_TARGET_BAND_STATUSES);

        List<PriceRevisionProvisionStateWriter.BandOutcome> outcomes = new ArrayList<>();
        for (PriceRevisionProvisionStateWriter.BandTarget band : plan.bands()) {
            outcomes.add(PriceRevisionProvisionSupport.attemptProvisionBand(
                    stripeProductRepository, gateway, plan.revisionId(),
                    plan.productKind(), plan.productKey(), band,
                    environmentIdentifier.environmentId()));
        }

        return stateWriter.complete(id, plan.lockVersion(), outcomes, actorId, AuditEventType.PRICE_REVISION_RETRY_PROVISIONED);
    }
}
