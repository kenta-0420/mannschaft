package com.mannschaft.app.billing.api;

import com.mannschaft.app.billing.BillingPriceBandVersionRepository;
import com.mannschaft.app.billing.BillingPriceProvisionGateway;
import com.mannschaft.app.billing.BillingPriceVersionRepository;
import com.mannschaft.app.billing.BillingPriceVersionStatus;
import com.mannschaft.app.billing.BillingProductKind;
import com.mannschaft.app.billing.BillingStripeProductRepository;
import com.mannschaft.app.billing.BillingTaxBehavior;
import com.mannschaft.app.billing.EntitlementScopeKind;
import com.mannschaft.app.billing.PlanEntity;
import com.mannschaft.app.billing.PlanRepository;
import com.mannschaft.app.billing.api.dto.PriceBandInput;
import com.mannschaft.app.billing.api.dto.PriceRevisionCreateRequest;
import com.mannschaft.app.billing.api.dto.PriceRevisionResponse;
import com.mannschaft.app.billing.tax.BillingTaxCodeEntity;
import com.mannschaft.app.billing.tax.BillingTaxCodeRepository;
import com.mannschaft.app.support.test.AbstractMySqlIntegrationTest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verify;

/**
 * 価格改定 Provision の実 DB 検証（Codex 検分 P1 指摘1・指摘2の検体）。
 *
 * <ul>
 *   <li><b>指摘1（税コード）</b>: Stripe Product の {@code tax_code} には税コードマスタの内部 {@code code}
 *       （{@code JP_...}）ではなく {@code stripe_tax_code}（{@code txcd_...}）が渡らなければならない
 *       （陣立て書 決定7・AC-84）。revision create → provision を本番と同じ Spring Bean で通し、
 *       Gateway に渡った {@code ProductResolutionCommand#stripeTaxCode} を捕捉して直接検証する。</li>
 *   <li><b>指摘2（PROVISIONING の commit）</b>: Stripe を呼ぶ時点で PROVISIONING が別トランザクションから
 *       見える（＝commit 済み）こと、および Stripe 呼び出し中にプロセスが死んだ（ここでは {@link Error}
 *       で模擬）場合でも PROVISIONING が DB に残り reconcile の回収対象になること（AC-68）。
 *       観測は Gateway 呼び出しの中から<b>別スレッド</b>（＝トランザクション同期に束縛されない別コネクション）
 *       で行う。同一スレッドで読むと呼び出し元の未 commit トランザクションに相乗りして偽 green になる。</li>
 * </ul>
 *
 * <p>Service は {@code new} で生成せず {@code @Autowired} する（手動生成では AOP のトランザクション
 * プロキシが効かず、検証したい tx 境界そのものが消える）。Gateway のみ Stripe 通信を避けるため
 * {@code @MockitoBean} に差し替える（tx の挙動には関与しない）。</p>
 */
@EnabledIf("com.mannschaft.app.support.test.AbstractMySqlIntegrationTest#isDockerAvailable")
@DisplayName("価格改定 Provision の tx 境界・Stripe 税コード IT")
class PriceRevisionProvisionTransactionIT extends AbstractMySqlIntegrationTest {

    private static final String TAX_CODE = "JP_PRV_IT_10";
    private static final String STRIPE_TAX_CODE = "txcd_99999999";

    @MockitoBean
    private BillingPriceProvisionGateway gateway;

    @Autowired
    private PriceRevisionCreateService createService;
    @Autowired
    private PriceRevisionProvisionService provisionService;
    @Autowired
    private BillingPriceVersionRepository versionRepository;
    @Autowired
    private BillingPriceBandVersionRepository bandRepository;
    @Autowired
    private BillingStripeProductRepository stripeProductRepository;
    @Autowired
    private PlanRepository planRepository;
    @Autowired
    private BillingTaxCodeRepository taxCodeRepository;

    private final ExecutorService observer = Executors.newSingleThreadExecutor();

    @BeforeEach
    void setUp() {
        if (taxCodeRepository.findByCodeAndValidFromAndDeletedAtIsNull(TAX_CODE, Instant.EPOCH).isEmpty()) {
            taxCodeRepository.save(BillingTaxCodeEntity.builder()
                    .code(TAX_CODE).displayName("IT標準税率10%").rateBasisPoints(1000)
                    .stripeTaxCode(STRIPE_TAX_CODE)
                    .validFrom(Instant.EPOCH).enabled(true).build());
        }
        given(gateway.findPriceByMetadata(any(), any())).willReturn(Optional.empty());
    }

    @AfterEach
    void tearDown() {
        observer.shutdownNow();
    }

    /** テストごとに別商品（単一 future 制限に他テストの DRAFT が干渉しないように）。 */
    private PriceRevisionResponse createDraft() {
        String planKey = "PRVIT" + UUID.randomUUID().toString().replace("-", "").substring(0, 10).toUpperCase();
        planRepository.save(PlanEntity.builder().planKey(planKey).enabled(true)
                .displayNameKey("k").descriptionKey("d").sortOrder(1).build());
        PriceRevisionCreateRequest request = new PriceRevisionCreateRequest(
                BillingProductKind.PLAN, planKey, EntitlementScopeKind.TEAM,
                Instant.now().plusSeconds(3600), null,
                List.of(new PriceBandInput(1, 1, null, 1000L, BillingTaxBehavior.EXCLUSIVE, TAX_CODE)));
        PriceRevisionResponse draft = createService.create(request, 1L);
        // create 応答の band id は後続操作（観測・reconcile）の鍵。null なら create 側の欠陥。
        assertThat(draft.getBands()).allSatisfy(b -> assertThat(b.getId()).isNotNull());
        return draft;
    }

    @Test
    @DisplayName("指摘1/AC-84: Stripe Product 解決に渡る税コードは税コードマスタの stripeTaxCode（内部 code ではない）")
    void stripeTaxCodeFromMasterIsPassedToGateway() {
        PriceRevisionResponse draft = createDraft();
        given(gateway.resolveOrCreateProduct(any()))
                .willReturn(new BillingPriceProvisionGateway.ProductResolution("prod_it_tax", true));
        given(gateway.createPrice(any()))
                .willReturn(new BillingPriceProvisionGateway.PriceCreationResult("price_it_tax"));
        ArgumentCaptor<BillingPriceProvisionGateway.ProductResolutionCommand> captor =
                ArgumentCaptor.forClass(BillingPriceProvisionGateway.ProductResolutionCommand.class);

        PriceRevisionResponse result = provisionService.provision(draft.getId(), draft.getLockVersion(), 1L);

        verify(gateway).resolveOrCreateProduct(captor.capture());
        assertThat(captor.getValue().stripeTaxCode())
                .as("Stripe Product の tax_code は税コードマスタの stripe_tax_code でなければならない")
                .isEqualTo(STRIPE_TAX_CODE);
        assertThat(result.getStatus()).isEqualTo(BillingPriceVersionStatus.READY);
        // 応答の taxCode は内部 code のまま（決定7: API・DTO の taxCode は内部 code）。
        assertThat(result.getBands().get(0).getTaxCode()).isEqualTo(TAX_CODE);
        // Product 解決キー（決定9改訂）も Stripe 側税コードで永続化される。
        assertThat(stripeProductRepository.findByProductKindAndProductKeyAndStripeTaxCode(
                BillingProductKind.PLAN, draft.getProductKey(), STRIPE_TAX_CODE)).isPresent();
    }

    @Test
    @DisplayName("指摘2/AC-68: Stripe 呼び出し時点で PROVISIONING が別コネクションから見え、呼び出し中に落ちても PROVISIONING が残る")
    void provisioningIsCommittedBeforeStripeCallAndSurvivesCrash() {
        PriceRevisionResponse draft = createDraft();
        UUID revisionId = draft.getId();
        UUID bandId = draft.getBands().get(0).getId();
        AtomicReference<BillingPriceVersionStatus> revisionSeenByOtherTx = new AtomicReference<>();
        AtomicReference<BillingPriceVersionStatus> bandSeenByOtherTx = new AtomicReference<>();

        AtomicReference<Throwable> observationFailure = new AtomicReference<>();
        given(gateway.resolveOrCreateProduct(any())).willAnswer(invocation -> {
            // 観測の失敗を RuntimeException で投げると fail-forward の catch に飲まれて原因が消えるため、
            // 記録だけして後で assert する。
            try {
                revisionSeenByOtherTx.set(observeInOtherThread(
                        () -> versionRepository.findById(revisionId).orElseThrow().getStatus()));
                bandSeenByOtherTx.set(observeInOtherThread(
                        () -> bandRepository.findById(bandId).orElseThrow().getStatus()));
            } catch (RuntimeException e) {
                observationFailure.set(e);
            }
            // Stripe 呼び出し中のプロセス停止を模擬する（fail-forward の catch(RuntimeException) を素通りする）。
            throw new SimulatedProcessDeath();
        });

        assertThatThrownBy(() -> provisionService.provision(revisionId, draft.getLockVersion(), 1L))
                .as("Gateway の中で模擬したプロセス停止が呼び出し元まで伝播すること"
                        + "（伝播しない場合は Gateway 到達前に band が失敗している）")
                .isInstanceOf(SimulatedProcessDeath.class);
        assertThat(observationFailure.get()).as("別スレッドからの DB 観測自体が失敗していないこと").isNull();

        assertThat(bandSeenByOtherTx.get())
                .as("Stripe 呼び出し時点で band の PROVISIONING が commit 済みでなければならない")
                .isEqualTo(BillingPriceVersionStatus.PROVISIONING);
        assertThat(revisionSeenByOtherTx.get())
                .as("Stripe 呼び出し時点で revision の PROVISIONING が commit 済みでなければならない")
                .isEqualTo(BillingPriceVersionStatus.PROVISIONING);
        assertThat(observeInOtherThread(() -> bandRepository.findById(bandId).orElseThrow().getStatus()))
                .as("Stripe 呼び出し中に落ちても band は PROVISIONING のまま残り reconcile の回収対象になる")
                .isEqualTo(BillingPriceVersionStatus.PROVISIONING);
        assertThat(observeInOtherThread(() -> versionRepository.findById(revisionId).orElseThrow().getStatus()))
                .isEqualTo(BillingPriceVersionStatus.PROVISIONING);
    }

    private <T> T observeInOtherThread(java.util.concurrent.Callable<T> query) throws RuntimeException {
        try {
            return observer.submit(query).get(30, TimeUnit.SECONDS);
        } catch (Exception e) {
            Throwable root = e;
            while (root.getCause() != null) {
                root = root.getCause();
            }
            throw new IllegalStateException("別スレッドからの DB 観測に失敗しました: "
                    + root.getClass().getName() + ": " + root.getMessage(), e);
        }
    }

    /** Stripe 呼び出し中のプロセス停止の模擬。RuntimeException ではないため fail-forward に捕まらない。 */
    private static final class SimulatedProcessDeath extends Error {
        private static final long serialVersionUID = 1L;

        SimulatedProcessDeath() {
            super("simulated process death during Stripe call");
        }
    }
}
