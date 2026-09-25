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
import java.util.UUID;

/**
 * 試練隊（第2陣）E群: {@code POST /price-revisions/{id}/provision}（決定2・決定9改訂）。
 *
 * <p>同期実行で常に200＋終局状態（READY/PROVISION_FAILED）を返す。202は返さない。
 * DB へ revision・band の PROVISIONING を <b>commit した後にのみ</b> Stripe を呼ぶ（AC-68）。
 * fail-forward: 1 band の失敗で以降を中断しない（AC-72）。</p>
 *
 * <p>本メソッド自体は {@code @Transactional} にしない（AC-69: Stripe 呼び出しを DB トランザクションに
 * 含めない）。DB の状態遷移は別 Bean {@link PriceRevisionProvisionStateWriter} の2つの独立トランザクション
 * （begin / complete）で行い、その間でトランザクション外に Stripe を呼ぶ。Stripe 呼び出し中に
 * プロセスが止まっても PROVISIONING が DB に残り、reconcile-provision で回収できる。</p>
 *
 * <p>正本: {@code .claude/campaigns/price-rev-plan-v3.md} 決定2・決定9改訂・AC-66〜AC-88d。</p>
 */
@Service
public class PriceRevisionProvisionService {

    private final PriceRevisionProvisionStateWriter stateWriter;
    private final BillingStripeProductRepository stripeProductRepository;
    private final BillingPriceProvisionGateway gateway;
    private final StripeEnvironmentIdentifier environmentIdentifier;

    public PriceRevisionProvisionService(
            PriceRevisionProvisionStateWriter stateWriter,
            BillingStripeProductRepository stripeProductRepository,
            BillingPriceProvisionGateway gateway,
            StripeEnvironmentIdentifier environmentIdentifier) {
        this.stateWriter = stateWriter;
        this.stripeProductRepository = stripeProductRepository;
        this.gateway = gateway;
        this.environmentIdentifier = environmentIdentifier;
    }

    public PriceRevisionResponse provision(UUID id, long lockVersion, Long actorId) {
        // AC-76/AC-77/AC-78: CAS と「DRAFT からのみ」の検査の後、PROVISIONING を commit する。
        // provision は revision 配下の全 band を対象にする。
        PriceRevisionProvisionStateWriter.ProvisionPlan plan = stateWriter.begin(
                id, lockVersion, BillingPriceVersionStatus.DRAFT, EnumSet.allOf(BillingPriceVersionStatus.class));

        List<PriceRevisionProvisionStateWriter.BandOutcome> outcomes = new ArrayList<>();
        for (PriceRevisionProvisionStateWriter.BandTarget band : plan.bands()) {
            outcomes.add(PriceRevisionProvisionSupport.attemptProvisionBand(
                    stripeProductRepository, gateway, plan.revisionId(),
                    plan.productKind(), plan.productKey(), band,
                    environmentIdentifier.environmentId()));
        }

        return stateWriter.complete(id, plan.lockVersion(), outcomes, actorId, AuditEventType.PRICE_REVISION_PROVISIONED);
    }
}
