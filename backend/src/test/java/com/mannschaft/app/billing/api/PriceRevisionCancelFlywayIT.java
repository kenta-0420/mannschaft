package com.mannschaft.app.billing.api;

import com.mannschaft.app.billing.BillingPriceBandVersionEntity;
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
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.testcontainers.DockerClientFactory;
import org.testcontainers.containers.MySQLContainer;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;

/**
 * 価格改定の取り消しを <b>Flyway の実 DDL</b> を当てた DB で通す（実機E2E で取り消しが常に500だった欠陥の検体）。
 *
 * <p>通常の IT（{@code AbstractMySqlIntegrationTest}）は {@code ddl-auto=create} で Entity からスキーマを作るため、
 * migration が張る CHECK 制約（{@code chk_bpbv_active} など）が存在しない。V227 で状態一覧に CANCELLED を
 * 足した際に {@code chk_bpbv_active}（状態と {@code stripe_price_ref} の組み合わせ）を張り直し忘れ、
 * band を CANCELLED に更新すると {@code SQL Error 3819: Check constraint 'chk_bpbv_active' is violated} で
 * 500 になっていたが、既存 IT はこれを原理的に検出できなかった。本 IT は {@code spring.flyway.enabled=true} +
 * {@code ddl-auto=none} で本番と同じ DDL の上に本番と同じ Bean を載せ、取り消し可能な3状態
 * （DRAFT / READY / PROVISION_FAILED）すべてから取り消せることを確かめる。</p>
 */
@SpringBootTest(properties = {
        "spring.flyway.enabled=true",
        "spring.jpa.hibernate.ddl-auto=none"
})
@ActiveProfiles("test")
@EnabledIf("com.mannschaft.app.billing.api.PriceRevisionCancelFlywayIT#isDockerAvailable")
@DisplayName("価格改定の取り消し（Flyway 実スキーマ）: DRAFT / READY / PROVISION_FAILED から CANCELLED にできる")
class PriceRevisionCancelFlywayIT {

    private static final String TAX_CODE = "JP_CNL_FW_10";

    @SuppressWarnings("resource")
    static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.0")
            .withDatabaseName("mannschaft_price_revision_cancel_flyway")
            .withUsername("test")
            .withPassword("test")
            .withTmpFs(Map.of("/var/lib/mysql", "rw"))
            .withCommand("--log_bin_trust_function_creators=1");

    static {
        if (isDockerAvailable()) {
            MYSQL.start();
        }
    }

    @MockitoBean
    private org.springframework.data.redis.core.StringRedisTemplate redisTemplate;

    @MockitoBean
    private BillingPriceProvisionGateway gateway;

    @DynamicPropertySource
    static void configureProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", MYSQL::getJdbcUrl);
        registry.add("spring.datasource.username", MYSQL::getUsername);
        registry.add("spring.datasource.password", MYSQL::getPassword);
    }

    public static boolean isDockerAvailable() {
        try {
            return DockerClientFactory.instance().isDockerAvailable();
        } catch (Exception e) {
            return false;
        }
    }

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
    @Autowired
    private org.springframework.jdbc.core.JdbcTemplate jdbcTemplate;

    @BeforeEach
    void setUp() {
        if (taxCodeRepository.findByCodeAndValidFromAndDeletedAtIsNull(TAX_CODE, Instant.EPOCH).isEmpty()) {
            taxCodeRepository.save(BillingTaxCodeEntity.builder()
                    .code(TAX_CODE).displayName("FW標準税率10%").rateBasisPoints(1000)
                    .stripeTaxCode("txcd_99999999")
                    .validFrom(Instant.EPOCH).enabled(true).build());
        }
        given(gateway.findPriceByMetadata(any(), any())).willReturn(Optional.empty());
    }

    @Test
    @DisplayName("DRAFT（stripe_price_ref は NULL）から取り消せる")
    void cancelFromDraft() {
        PriceRevisionResponse draft = createService.create(request(newPlan()), 1L);
        assertThat(draft.getStatus()).isEqualTo(BillingPriceVersionStatus.DRAFT);

        assertCancelled(draft, false);
    }

    @Test
    @DisplayName("READY（stripe_price_ref は非 NULL）から取り消せ、Price の参照は保持される")
    void cancelFromReady() {
        PriceRevisionResponse draft = createService.create(request(newPlan()), 1L);
        given(gateway.resolveOrCreateProduct(any()))
                .willReturn(new BillingPriceProvisionGateway.ProductResolution("prod_fw_" + draft.getId(), true));
        given(gateway.createPrice(any())).willAnswer(invocation -> new BillingPriceProvisionGateway.PriceCreationResult(
                "price_fw_" + UUID.randomUUID().toString().replace("-", "")));
        PriceRevisionResponse ready = provisionService.provision(draft.getId(), draft.getLockVersion(), 1L);
        assertThat(ready.getStatus()).isEqualTo(BillingPriceVersionStatus.READY);

        assertCancelled(ready, true);
    }

    @Test
    @DisplayName("PROVISION_FAILED（stripe_price_ref は NULL）から取り消せ、同じ商品に新しい DRAFT を作れる")
    void cancelFromProvisionFailed() {
        String planKey = newPlan();
        PriceRevisionResponse draft = createService.create(request(planKey), 1L);
        given(gateway.resolveOrCreateProduct(any())).willThrow(new IllegalStateException("tax_code rejected"));
        PriceRevisionResponse failed = provisionService.provision(draft.getId(), draft.getLockVersion(), 1L);
        assertThat(failed.getStatus()).isEqualTo(BillingPriceVersionStatus.PROVISION_FAILED);

        assertCancelled(failed, false);

        PriceRevisionResponse next = createService.create(request(planKey), 1L);
        assertThat(next.getStatus()).as("取り消し後は同じ商品に新しい DRAFT を作れる（future 枠の解放）")
                .isEqualTo(BillingPriceVersionStatus.DRAFT);
    }

    /**
     * 状態を列挙した CHECK と Java の {@link BillingPriceVersionStatus} の食い違いを検出する番人。
     *
     * <p>V227 は {@code chk_bpv_status} / {@code chk_bpbv_status} に CANCELLED を足したが、同じく状態を列挙する
     * {@code chk_bpbv_active} を張り直し忘れた。状態を列挙する CHECK は enum の全値に言及していなければならない
     * （言及の無い状態は、その CHECK を常に満たせない＝その状態への遷移が必ず 3819 で落ちる）。</p>
     */
    @Test
    @DisplayName("状態を列挙する CHECK（chk_bpv_status / chk_bpbv_status / chk_bpbv_active）は enum の全状態に言及する")
    void statusChecksMentionEveryEnumValue() {
        for (String constraint : List.of("chk_bpv_status", "chk_bpbv_status", "chk_bpbv_active")) {
            String rawClause = jdbcTemplate.queryForObject(
                    "SELECT CHECK_CLAUSE FROM information_schema.CHECK_CONSTRAINTS "
                            + "WHERE CONSTRAINT_SCHEMA = DATABASE() AND CONSTRAINT_NAME = ?",
                    String.class, constraint);
            // MySQL は information_schema.CHECK_CONSTRAINTS.CHECK_CLAUSE のクォートを `\'` にエスケープして返すため、
            // 素の `'` に正規化してから enum 全値への言及を検査する（検証の厳密さは変えない）。
            String clause = rawClause.replace("\\'", "'");
            for (BillingPriceVersionStatus status : BillingPriceVersionStatus.values()) {
                assertThat(clause).as("%s が状態 %s に言及していること", constraint, status)
                        .contains("'" + status.name() + "'");
            }
        }
    }

    private void assertCancelled(PriceRevisionResponse before, boolean expectPriceRef) {
        PriceRevisionResponse cancelled = cancelService.cancel(before.getId(), before.getLockVersion(), 1L);

        assertThat(cancelled.getStatus()).isEqualTo(BillingPriceVersionStatus.CANCELLED);
        assertThat(versionRepository.findById(before.getId()).orElseThrow().getStatus())
                .isEqualTo(BillingPriceVersionStatus.CANCELLED);
        for (var bandResponse : before.getBands()) {
            BillingPriceBandVersionEntity band = bandRepository.findById(bandResponse.getId()).orElseThrow();
            assertThat(band.getStatus()).isEqualTo(BillingPriceVersionStatus.CANCELLED);
            if (expectPriceRef) {
                assertThat(band.getStripePriceRef()).as("READY から取り消した band は Price の参照を保持する").isNotNull();
            } else {
                assertThat(band.getStripePriceRef()).isNull();
            }
        }
    }

    private String newPlan() {
        String planKey = "CNLFW" + UUID.randomUUID().toString().replace("-", "").substring(0, 10).toUpperCase();
        planRepository.save(PlanEntity.builder().planKey(planKey).enabled(true)
                .displayNameKey("k").descriptionKey("d").sortOrder(1).build());
        return planKey;
    }

    private static PriceRevisionCreateRequest request(String planKey) {
        return new PriceRevisionCreateRequest(BillingProductKind.PLAN, planKey, EntitlementScopeKind.TEAM,
                Instant.now().plusSeconds(3600), null,
                List.of(new PriceBandInput(1, 1, null, 1000L, BillingTaxBehavior.EXCLUSIVE, TAX_CODE)));
    }
}
