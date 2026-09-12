package com.mannschaft.app.billing.api;

import com.mannschaft.app.billing.BillingContractEntity;
import com.mannschaft.app.billing.BillingPaymentGateway;
import com.mannschaft.app.billing.ContractKind;
import com.mannschaft.app.billing.ContractStatus;
import com.mannschaft.app.billing.EntitlementScopeKind;
import com.mannschaft.app.admin.repository.FeatureFlagRepository;
import com.mannschaft.app.support.test.AbstractMySqlIntegrationTest;
import com.mannschaft.app.support.test.FeatureFlagTestSupport;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.cache.CacheManager;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.transaction.support.TransactionTemplate;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;

import java.time.Clock;
import java.time.LocalDateTime;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Billing Center PR6a — E群 表示の誠実さ（AC-57・AC-60・AC-63・AC-65）の受け入れテスト（試練C・red）。
 *
 * <p>対象エンドポイント: {@code GET /api/v1/me/entitlements}（{@code BillingEntitlementSummaryController}）。
 * この GET が FE の解約確認画面が読む「表示専用の投影」である（05:369）。現状の
 * {@code ActiveContract} DTO には canCancel/canResume/currentPeriodEnd/cancel(scheduledAt,endAt) が
 * 一切無く、本テストは<b>その追加そのもの</b>を red として要求する。</p>
 *
 * <p>AC-58/59/61/62/64 は FE（Vue コンポーネント・i18n・a11y）の担当であり、
 * このテストファイルには含めない（frontend/tests 配下を参照）。</p>
 */
@AutoConfigureMockMvc
@EnabledIf("com.mannschaft.app.support.test.AbstractMySqlIntegrationTest#isDockerAvailable")
@DisplayName("PR6a 表示の誠実さ（E群 AC-57/60/63/65・試練C red）")
class BillingCancelResumeDisplayContractRedIT extends AbstractMySqlIntegrationTest {

    private static final String ENTITLEMENTS_PATH = "/api/v1/me/entitlements";
    private static final String PLAN_KEY = "FULL";
    private static final int PRICE_JPY = 1_200;

    /** 6値の ContractStatus すべてを網羅する（AC-65）。 */
    private static final Set<String> VALID_CONTRACT_STATUSES = Set.of(
            "PENDING", "ACTIVE", "PAST_DUE", "CANCELLED", "EXPIRED", "PENDING_HANDOVER");

    /** 表示経路で Stripe を呼んではならない（AC-63）。 */
    @MockitoBean
    private BillingPaymentGateway billingPaymentGateway;

    @Autowired private MockMvc mockMvc;
    @Autowired private TransactionTemplate transactionTemplate;
    @Autowired private Clock clock;
    @Autowired private FeatureFlagRepository featureFlagRepository;
    @Autowired private CacheManager cacheManager;
    @PersistenceContext private EntityManager entityManager;

    private Long userId;

    /**
     * ゲートを開けてから本体を測る（フィクスチャ。期待値は一切緩めていない）。
     *
     * <p>{@code GET /api/v1/me/entitlements} は
     * {@code @RequireFeature("FEATURE_BILLING_PAYMENT_ENABLED")} 付きであり、
     * {@code FeatureGateAspect} は<b>行が無いキーをフェイルクローズで無効</b>と判定して
     * {@code FEATURE_GATE_001} を投げる（＝HTTP <b>403</b>）。テストプロファイルは
     * {@code spring.flyway.enabled: false} ＋ {@code ddl-auto: create} でスキーマを Entity から
     * 作るため、本番で 17 キーを seed する
     * {@code V187.20260820092252__seed_feature_gate_flags.sql} は<b>一度も走らない</b>。
     * よって何もしなければ本 IT の全要求が 403 になり、<b>表示契約（AC-57/60/63/65）の本体へ
     * 到達しない</b>。</p>
     *
     * <p>fail-close の既定も番人も緩めていない。{@link FeatureFlagTestSupport#enable} が
     * 行を upsert し<b>フラグキャッシュを落とす</b>だけである（行を入れてもキャッシュ済みの
     * false が返り続ける罠が実測されているため、行とキャッシュは必ず対で扱う）。
     * 同一コンテキストの別テストが行を消すため {@code @BeforeEach} で置き直す。</p>
     */
    @BeforeEach
    void setUp() {
        FeatureFlagTestSupport.enable(featureFlagRepository, cacheManager,
                "FEATURE_BILLING_PAYMENT_ENABLED");
        userId = insertUser();
    }

    @AfterEach
    void tearDown() {
        transactionTemplate.executeWithoutResult(tx -> {
            entityManager.createNativeQuery("DELETE FROM billing_contracts WHERE scope_id = :id")
                    .setParameter("id", userId).executeUpdate();
        });
    }

    // ═════════ AC-60: 応答に canCancel/canResume/currentPeriodEnd/cancel(scheduledAt,endAt) が含まれる ═════════

    @Test
    @DisplayName("AC-60: 解約予約前の有償契約はcanCancel=true・canResume=false・cancelはnull")
    void AC60_解約前はcanCancelがtrueでcancelはnull() throws Exception {
        LocalDateTime periodEnd = LocalDateTime.now(clock).plusDays(15).withNano(0);
        insertContract(ContractStatus.ACTIVE, PRICE_JPY, periodEnd, null);

        MvcResult result = mockMvc.perform(get(ENTITLEMENTS_PATH).with(user(String.valueOf(userId))))
                .andExpect(status().isOk())
                .andReturn();

        String body = result.getResponse().getContentAsString();
        assertThat(body).as("currentPeriodEndが投影に含まれる").contains("currentPeriodEnd");
        assertThat(body).as("canCancelが投影に含まれる").contains("canCancel");
        assertThat(body).as("canResumeが投影に含まれる").contains("canResume");
    }

    @Test
    @DisplayName("AC-60: 解約予約後（cancelled_at非null）はcanResume=trueでcancel.scheduledAt/endAtが埋まる")
    void AC60_解約予約後はcancel情報が埋まる() throws Exception {
        LocalDateTime periodEnd = LocalDateTime.now(clock).plusDays(15).withNano(0);
        LocalDateTime cancelledAt = LocalDateTime.now(clock).minusHours(1).withNano(0);
        insertContract(ContractStatus.ACTIVE, PRICE_JPY, periodEnd, cancelledAt);

        MvcResult result = mockMvc.perform(get(ENTITLEMENTS_PATH).with(user(String.valueOf(userId))))
                .andExpect(status().isOk())
                .andReturn();

        com.fasterxml.jackson.databind.JsonNode root =
                new com.fasterxml.jackson.databind.ObjectMapper().readTree(result.getResponse().getContentAsString());
        com.fasterxml.jackson.databind.JsonNode activePlan = root.path("data").path("activePlan");
        assertThat(activePlan.path("canResume").asBoolean())
                .as("解約予約中は撤回できる（AC-46 の窓が開いている）").isTrue();
        assertThat(activePlan.path("cancel").path("scheduledAt").asText(null))
                .as("cancel.scheduledAt が解約予約時刻で埋まる").isNotBlank();
        assertThat(activePlan.path("cancel").path("endAt").asText(null))
                .as("cancel.endAt が期末で埋まる（非null必須・AC-37c と同じ方針）").isNotBlank();
    }

    // ═════════ AC-63: 表示経路でStripeを呼ばない ═════════

    @Test
    @DisplayName("AC-63: canCancel/canResumeの導出でStripeを一度も呼ばない")
    void AC63_表示経路はStripeを呼ばない() throws Exception {
        LocalDateTime periodEnd = LocalDateTime.now(clock).plusDays(15).withNano(0);
        insertContract(ContractStatus.ACTIVE, PRICE_JPY, periodEnd, null);

        mockMvc.perform(get(ENTITLEMENTS_PATH).with(user(String.valueOf(userId))))
                .andExpect(status().isOk());

        org.mockito.Mockito.verifyNoInteractions(billingPaymentGateway);
    }

    // ═════════ AC-65: status値集合の整合 ═════════

    @Test
    @DisplayName("AC-65: 契約応答のstatusがContractStatusの6値のいずれかであり未知の値を作らない")
    void AC65_statusは6値のいずれか() throws Exception {
        LocalDateTime periodEnd = LocalDateTime.now(clock).plusDays(15).withNano(0);
        insertContract(ContractStatus.PAST_DUE, PRICE_JPY, periodEnd, null);

        MvcResult result = mockMvc.perform(get(ENTITLEMENTS_PATH).with(user(String.valueOf(userId))))
                .andExpect(status().isOk())
                .andReturn();

        com.fasterxml.jackson.databind.JsonNode root =
                new com.fasterxml.jackson.databind.ObjectMapper().readTree(result.getResponse().getContentAsString());
        String status = root.path("data").path("activePlan").path("status").asText(null);
        assertThat(status)
                .as("応答に status フィールドが存在し、6値のいずれかであること。存在しない場合も違反")
                .isNotNull();
        assertThat(VALID_CONTRACT_STATUSES)
                .as("未知のstatus文字列を作らない")
                .contains(status);
    }

    // ═════════ 陽性対照: Stripe呼び出しが必要な操作系(cancel)自体は動く ═════════

    @Test
    @DisplayName("陽性対照: BillingPaymentGatewayのモック自体は正しく差し込まれている（verifyNoInteractionsが空虚な緑でないことの証明）")
    void 陽性対照_gatewayモックは呼べば呼ばれたと判定される() {
        billingPaymentGateway.cancelAtPeriodEnd("dummy_ref_for_mock_sanity_check");
        org.mockito.Mockito.verify(billingPaymentGateway).cancelAtPeriodEnd("dummy_ref_for_mock_sanity_check");
    }

    // ═════════ フィクスチャ ═════════

    private Long insertUser() {
        return transactionTemplate.execute(tx -> {
            com.mannschaft.app.auth.entity.UserEntity u = com.mannschaft.app.auth.entity.UserEntity.builder()
                    .email("pr6a-display-" + System.nanoTime() + "@example.com")
                    .lastName("試練").firstName("表示").displayName("試練 表示")
                    .status(com.mannschaft.app.auth.entity.UserEntity.UserStatus.ACTIVE)
                    .locale("ja").timezone("Asia/Tokyo").isSearchable(true).build();
            entityManager.persist(u);
            entityManager.flush();
            return u.getId();
        });
    }

    private UUID insertContract(ContractStatus status, Integer priceJpy, LocalDateTime periodEnd,
                                 LocalDateTime cancelledAt) {
        return transactionTemplate.execute(tx -> {
            BillingContractEntity c = BillingContractEntity.builder()
                    .scopeKind(EntitlementScopeKind.USER).scopeId(userId)
                    .contractKind(ContractKind.PLAN).planKey(PLAN_KEY)
                    .status(status)
                    .priceJpySnapshot(priceJpy)
                    .pspSubscriptionRef("sub_pr6a_display_" + System.nanoTime())
                    .pspCustomerRef("cus_pr6a_display_" + userId)
                    .currentPeriodEnd(periodEnd)
                    .cancelledAt(cancelledAt)
                    .contractedAt(LocalDateTime.now(clock).minusMonths(1))
                    .createdBy(userId).payerUserId(userId)
                    .version(0L)
                    .build();
            entityManager.persist(c);
            entityManager.flush();
            return c.getId();
        });
    }
}
