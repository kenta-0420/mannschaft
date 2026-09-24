package com.mannschaft.app.billing.api;

import com.mannschaft.app.billing.BillingPriceBandVersionRepository;
import com.mannschaft.app.billing.BillingPriceProvisionGateway;
import com.mannschaft.app.billing.BillingPriceProvisionRecoveryService;
import com.mannschaft.app.billing.BillingPriceVersionRepository;
import com.mannschaft.app.billing.BillingPriceVersionStatus;
import com.mannschaft.app.billing.BillingProductKind;
import com.mannschaft.app.billing.BillingTaxBehavior;
import com.mannschaft.app.billing.EntitlementScopeKind;
import com.mannschaft.app.billing.PlanEntity;
import com.mannschaft.app.billing.PlanRepository;
import com.mannschaft.app.billing.api.dto.PriceBandInput;
import com.mannschaft.app.billing.api.dto.PriceRevisionCreateRequest;
import com.mannschaft.app.billing.api.dto.PriceRevisionResponse;
import com.mannschaft.app.billing.tax.BillingTaxCodeEntity;
import com.mannschaft.app.billing.tax.BillingTaxCodeRepository;
import com.mannschaft.app.payment.stripe.StripeEnvironmentIdentifier;
import com.mannschaft.app.support.test.AbstractMySqlIntegrationTest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;

/**
 * reconcile-provision の tx 境界を実 DB で検証する（AC-69 の兄弟経路・2026-09-24 殿の指示）。
 *
 * <p>reconcile は Stripe へ Price を照会する（{@code findPriceByMetadata}）。以前はメソッド全体が
 * {@code @Transactional} で、band 行を {@code FOR UPDATE} で握ったまま Stripe の応答を待っていた
 * （1コール最大16秒×band 数、その間 provision/activate/他の reconcile がロック待ちになる）。
 * Stripe 呼び出しの時点で revision/band の行ロックを保持していないことを、別スレッド（別コネクション）から
 * 短いロック待ちタイムアウトで {@code FOR UPDATE} を取れるかで直接観測する。</p>
 *
 * <p>PROVISIONING で停止した revision は、provision の Stripe 呼び出し中のプロセス停止（{@link Error} で模擬）
 * で本物の経路から作る。Service は {@code @Autowired}、Gateway のみ {@code @MockitoBean}。</p>
 */
@EnabledIf("com.mannschaft.app.support.test.AbstractMySqlIntegrationTest#isDockerAvailable")
@DisplayName("reconcile-provision の tx 境界 IT")
class PriceRevisionReconcileTransactionIT extends AbstractMySqlIntegrationTest {

    private static final String TAX_CODE = "JP_RCN_IT_10";
    private static final String STRIPE_TAX_CODE = "txcd_99999999";

    @MockitoBean
    private BillingPriceProvisionGateway gateway;

    @Autowired
    private PriceRevisionCreateService createService;
    @Autowired
    private PriceRevisionProvisionService provisionService;
    @Autowired
    private BillingPriceProvisionRecoveryService recoveryService;
    @Autowired
    private BillingPriceVersionRepository versionRepository;
    @Autowired
    private BillingPriceBandVersionRepository bandRepository;
    @Autowired
    private PlanRepository planRepository;
    @Autowired
    private BillingTaxCodeRepository taxCodeRepository;
    @Autowired
    private StripeEnvironmentIdentifier environmentIdentifier;
    @Autowired
    private PlatformTransactionManager transactionManager;
    @Autowired
    private JdbcTemplate jdbcTemplate;

    private final ExecutorService observer = Executors.newSingleThreadExecutor();

    @BeforeEach
    void setUp() {
        if (taxCodeRepository.findByCodeAndValidFromAndDeletedAtIsNull(TAX_CODE, Instant.EPOCH).isEmpty()) {
            taxCodeRepository.save(BillingTaxCodeEntity.builder()
                    .code(TAX_CODE).displayName("IT標準税率10%").rateBasisPoints(1000)
                    .stripeTaxCode(STRIPE_TAX_CODE)
                    .validFrom(Instant.EPOCH).enabled(true).build());
        }
    }

    @AfterEach
    void tearDown() {
        observer.shutdownNow();
    }

    @Test
    @DisplayName("AC-69: reconcile は Stripe 照会中に band 行のロックを保持せず、照合一致なら READY へ回収する")
    void reconcileDoesNotHoldRowLocksDuringStripeCall() {
        PriceRevisionResponse draft = stuckProvisioningRevision();
        UUID revisionId = draft.getId();
        UUID bandId = draft.getBands().get(0).getId();
        long lockVersion = versionRepository.findById(revisionId).orElseThrow().getLockVersion();
        String productKey = versionRepository.findById(revisionId).orElseThrow().getProductKey();

        AtomicReference<String> lockAttempt = new AtomicReference<>();
        given(gateway.findPriceByMetadata(any(), any())).willAnswer(invocation -> {
            lockAttempt.set(tryLockBandsFromOtherConnection(revisionId));
            return Optional.of(new BillingPriceProvisionGateway.PriceSnapshot(
                    "price_rcn_it", 1000L, "jpy", "month", 1,
                    BillingProductKind.PLAN.name(), productKey, STRIPE_TAX_CODE,
                    BillingTaxBehavior.EXCLUSIVE.name(), environmentIdentifier.environmentId()));
        });

        PriceRevisionResponse result = recoveryService.reconcileProvision(revisionId, lockVersion, 1L);

        assertThat(lockAttempt.get())
                .as("Stripe 照会の時点で別コネクションから band 行を FOR UPDATE で取れること"
                        + "（取れない＝reconcile が DB トランザクションを開いたまま Stripe を呼んでいる）")
                .isEqualTo("LOCKED");
        assertThat(result.getStatus()).isEqualTo(BillingPriceVersionStatus.READY);
        assertThat(bandRepository.findById(bandId).orElseThrow().getStatus())
                .isEqualTo(BillingPriceVersionStatus.READY);
        assertThat(bandRepository.findById(bandId).orElseThrow().getStripePriceRef()).isEqualTo("price_rcn_it");
    }

    /** provision の Stripe 呼び出し中にプロセスが止まった状態（revision/band とも PROVISIONING）を本物の経路で作る。 */
    private PriceRevisionResponse stuckProvisioningRevision() {
        String planKey = "RCNIT" + UUID.randomUUID().toString().replace("-", "").substring(0, 10).toUpperCase();
        planRepository.save(PlanEntity.builder().planKey(planKey).enabled(true)
                .displayNameKey("k").descriptionKey("d").sortOrder(1).build());
        PriceRevisionResponse draft = createService.create(new PriceRevisionCreateRequest(
                BillingProductKind.PLAN, planKey, EntitlementScopeKind.TEAM,
                Instant.now().plusSeconds(3600), null,
                List.of(new PriceBandInput(1, 1, null, 1000L, BillingTaxBehavior.EXCLUSIVE, TAX_CODE))), 1L);
        given(gateway.findPriceByMetadata(any(), any())).willReturn(Optional.empty());
        given(gateway.resolveOrCreateProduct(any())).willThrow(new SimulatedProcessDeath());
        try {
            provisionService.provision(draft.getId(), draft.getLockVersion(), 1L);
        } catch (SimulatedProcessDeath expected) {
            // 模擬したプロセス停止。PROVISIONING が DB に残っていることを次で確認する。
        }
        assertThat(versionRepository.findById(draft.getId()).orElseThrow().getStatus())
                .as("前提: provision 途中停止で revision が PROVISIONING のまま残る")
                .isEqualTo(BillingPriceVersionStatus.PROVISIONING);
        return draft;
    }

    /**
     * 別スレッド・別トランザクションで、1秒のロック待ちタイムアウトを付けて band 行を FOR UPDATE する。
     * 取れれば "LOCKED"、ロック待ちで失敗すれば例外の要約を返す。
     */
    private String tryLockBandsFromOtherConnection(UUID revisionId) {
        try {
            return observer.submit(() -> new TransactionTemplate(transactionManager).execute(status -> {
                jdbcTemplate.execute("SET SESSION innodb_lock_wait_timeout = 1");
                try {
                    bandRepository.findAllByPriceVersionIdForUpdate(revisionId);
                    return "LOCKED";
                } catch (RuntimeException e) {
                    status.setRollbackOnly();
                    return "LOCK_FAILED: " + e.getClass().getSimpleName();
                } finally {
                    jdbcTemplate.execute("SET SESSION innodb_lock_wait_timeout = DEFAULT");
                }
            })).get(30, TimeUnit.SECONDS);
        } catch (Exception e) {
            return "OBSERVATION_FAILED: " + e;
        }
    }

    /** プロセス停止の模擬。RuntimeException ではないため fail-forward の catch に捕まらない。 */
    private static final class SimulatedProcessDeath extends Error {
        private static final long serialVersionUID = 1L;

        SimulatedProcessDeath() {
            super("simulated process death during Stripe call");
        }
    }
}
