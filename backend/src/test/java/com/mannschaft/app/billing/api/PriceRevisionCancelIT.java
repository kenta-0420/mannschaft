package com.mannschaft.app.billing.api;

import com.mannschaft.app.billing.BillingPriceBandVersionRepository;
import com.mannschaft.app.billing.BillingPriceProvisionGateway;
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
import com.mannschaft.app.support.test.AbstractMySqlIntegrationTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;

/**
 * 価格改定の取り消しが「永久ロック」の出口として実際に機能することを実 DB で検証する（御裁可 2026-09-24）。
 *
 * <p>修復できない失敗で PROVISION_FAILED になった revision は、単一 future 制限（アプリ判定と
 * {@code uk_bpv_single_future}）でその商品の新規 DRAFT 作成を塞ぐ。取り消し（CANCELLED）で枠が解放され、
 * 同じ商品に新しい DRAFT を作れることを、本番と同じ Bean（{@code @Autowired}）と実 DB の制約で確かめる。</p>
 */
@EnabledIf("com.mannschaft.app.support.test.AbstractMySqlIntegrationTest#isDockerAvailable")
@DisplayName("価格改定の取り消しで future 枠が解放される IT")
class PriceRevisionCancelIT extends AbstractMySqlIntegrationTest {

    private static final String TAX_CODE = "JP_CNL_IT_10";

    @MockitoBean
    private BillingPriceProvisionGateway gateway;

    @Autowired
    private PriceRevisionCreateService createService;
    @Autowired
    private PriceRevisionProvisionService provisionService;
    @Autowired
    private PriceRevisionCancelService cancelService;
    @Autowired
    private BillingPriceVersionRepository versionRepository;
    @Autowired
    private BillingPriceBandVersionRepository bandRepository;
    @Autowired
    private PlanRepository planRepository;
    @Autowired
    private BillingTaxCodeRepository taxCodeRepository;

    @BeforeEach
    void setUp() {
        if (taxCodeRepository.findByCodeAndValidFromAndDeletedAtIsNull(TAX_CODE, Instant.EPOCH).isEmpty()) {
            taxCodeRepository.save(BillingTaxCodeEntity.builder()
                    .code(TAX_CODE).displayName("IT標準税率10%").rateBasisPoints(1000)
                    .stripeTaxCode("txcd_99999999")
                    .validFrom(Instant.EPOCH).enabled(true).build());
        }
        given(gateway.findPriceByMetadata(any(), any())).willReturn(Optional.empty());
    }

    @Test
    @DisplayName("PROVISION_FAILED の revision を取り消すと revision/band が CANCELLED になり、同じ商品に新しい DRAFT を作れる")
    void cancellingProvisionFailedRevisionReleasesFutureSlot() {
        String planKey = "CNLIT" + UUID.randomUUID().toString().replace("-", "").substring(0, 10).toUpperCase();
        planRepository.save(PlanEntity.builder().planKey(planKey).enabled(true)
                .displayNameKey("k").descriptionKey("d").sortOrder(1).build());
        PriceRevisionResponse draft = createService.create(request(planKey), 1L);
        // 修復できない失敗の模擬（Stripe が常に拒否する）。
        given(gateway.resolveOrCreateProduct(any())).willThrow(new IllegalStateException("tax_code rejected"));
        PriceRevisionResponse failed = provisionService.provision(draft.getId(), draft.getLockVersion(), 1L);
        assertThat(failed.getStatus()).isEqualTo(BillingPriceVersionStatus.PROVISION_FAILED);

        PriceRevisionResponse cancelled = cancelService.cancel(failed.getId(), failed.getLockVersion(), 1L);

        assertThat(cancelled.getStatus()).isEqualTo(BillingPriceVersionStatus.CANCELLED);
        assertThat(versionRepository.findById(draft.getId()).orElseThrow().getStatus())
                .isEqualTo(BillingPriceVersionStatus.CANCELLED);
        assertThat(bandRepository.findById(draft.getBands().get(0).getId()).orElseThrow().getStatus())
                .isEqualTo(BillingPriceVersionStatus.CANCELLED);

        PriceRevisionResponse next = createService.create(request(planKey), 1L);
        assertThat(next.getStatus()).as("取り消し後は同じ商品に新しい DRAFT を作れる").isEqualTo(BillingPriceVersionStatus.DRAFT);
    }

    private static PriceRevisionCreateRequest request(String planKey) {
        return new PriceRevisionCreateRequest(BillingProductKind.PLAN, planKey, EntitlementScopeKind.TEAM,
                Instant.now().plusSeconds(3600), null,
                List.of(new PriceBandInput(1, 1, null, 1000L, BillingTaxBehavior.EXCLUSIVE, TAX_CODE)));
    }
}
