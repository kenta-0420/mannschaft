package com.mannschaft.app.billing.api;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.mannschaft.app.admin.repository.FeatureFlagRepository;
import com.mannschaft.app.billing.ActiveBillingContractOperationPointerEntity;
import com.mannschaft.app.billing.ActiveBillingContractOperationPointerRepository;
import com.mannschaft.app.billing.BillingContractChangeEntity;
import com.mannschaft.app.billing.BillingContractChangeKind;
import com.mannschaft.app.billing.BillingContractChangeRepository;
import com.mannschaft.app.billing.BillingContractChangeStatus;
import com.mannschaft.app.billing.BillingContractEntity;
import com.mannschaft.app.billing.BillingContractOperationEntity;
import com.mannschaft.app.billing.BillingContractOperationRepository;
import com.mannschaft.app.billing.BillingCustomerEntity;
import com.mannschaft.app.billing.BillingOperationActorKind;
import com.mannschaft.app.billing.BillingOperationKind;
import com.mannschaft.app.billing.BillingOperationStatus;
import com.mannschaft.app.billing.BillingOperationStep;
import com.mannschaft.app.billing.BillingPaymentGateway;
import com.mannschaft.app.billing.ContractKind;
import com.mannschaft.app.billing.ContractStatus;
import com.mannschaft.app.billing.EntitlementScopeKind;
import com.mannschaft.app.support.test.AbstractMySqlIntegrationTest;
import com.mannschaft.app.support.test.FeatureFlagTestSupport;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
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

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Billing Center PR6b-1 — F群（差し替え）<b>AC-104</b> の受け入れテスト（試練C・red）。
 *
 * <p>AC-104: 「支払い待ちであることは<b>操作を試みる前にも見える</b>（契約カードに状態が出ており、
 * 押してから初めて分かるのではない）」。BE 側の観測点は
 * {@code GET /api/v1/me/entitlements} が返す {@code BillingActiveContract} 投影に
 * {@code pendingChange}（{@code status} / {@code effectiveAt} / {@code paymentActionRequired}）が
 * 載ることである（FE の表示側・ポーリングは第5隊の担当。ここでは投影の存在だけを固定する）。</p>
 *
 * <h2>フェイルクローズの罠（実測済み・PR6a）</h2>
 * <p>{@code GET /api/v1/me/entitlements} は {@code @RequireFeature("FEATURE_BILLING_PAYMENT_ENABLED")}
 * 付きであり、テストプロファイルは Flyway を切って Entity から schema を作るため、本番 seed
 * migration が走らず {@code feature_flags} が空になる。何もしないと全要求が 403
 * （{@code FEATURE_GATE_001}）になり本体（pendingChange の有無）へ到達できない。
 * {@link FeatureFlagTestSupport#enable} で行の upsert とキャッシュ退避を対で行う。</p>
 *
 * <h2>空虚な緑への備え</h2>
 * <p>{@code pendingChange} が常に null を返す実装でも「フィールドが存在しない」ケースと
 * 区別が付かない緑を防ぐため、(1) 支払い待ち中は非 null で内容を検証する陽性テスト、
 * (2) 支払い待ちでない契約では null であることを確認する陰性対照、の両方を置く。</p>
 */
@AutoConfigureMockMvc
@EnabledIf("com.mannschaft.app.support.test.AbstractMySqlIntegrationTest#isDockerAvailable")
@DisplayName("PR6b-1 F群（差し替え）AC-104: pendingChangeは操作前にも見える（試練C red）")
class BillingEntitlementPendingChangeProjectionRedIT extends AbstractMySqlIntegrationTest {

    private static final String ENTITLEMENTS_PATH = "/api/v1/me/entitlements";
    private static final String FROM_PLAN_KEY = "BASIC";
    private static final String TO_PLAN_KEY = "FULL";

    /** 表示経路で Stripe を呼んではならない（AC-147 の裏取り。本体は AC-104）。 */
    @MockitoBean
    private BillingPaymentGateway billingPaymentGateway;

    @Autowired private MockMvc mockMvc;
    @Autowired private TransactionTemplate transactionTemplate;
    @Autowired private Clock clock;
    @Autowired private FeatureFlagRepository featureFlagRepository;
    @Autowired private CacheManager cacheManager;
    @Autowired private BillingContractOperationRepository operationRepository;
    @Autowired private BillingContractChangeRepository changeRepository;
    @Autowired private ActiveBillingContractOperationPointerRepository pointerRepository;
    @PersistenceContext private EntityManager entityManager;

    private Long userId;

    @BeforeEach
    void setUp() {
        FeatureFlagTestSupport.enable(featureFlagRepository, cacheManager,
                "FEATURE_BILLING_PAYMENT_ENABLED");
        userId = insertUser();
    }

    @AfterEach
    void tearDown() {
        transactionTemplate.executeWithoutResult(tx -> {
            entityManager.createNativeQuery(
                            "DELETE FROM billing_contract_changes WHERE contract_id IN "
                                    + "(SELECT id FROM billing_contracts WHERE scope_id = :id AND scope_kind = 'USER')")
                    .setParameter("id", userId).executeUpdate();
            entityManager.createNativeQuery(
                            "DELETE FROM active_billing_contract_operation_pointers WHERE contract_id IN "
                                    + "(SELECT id FROM billing_contracts WHERE scope_id = :id AND scope_kind = 'USER')")
                    .setParameter("id", userId).executeUpdate();
            entityManager.createNativeQuery(
                            "DELETE FROM billing_contract_operations WHERE contract_id IN "
                                    + "(SELECT id FROM billing_contracts WHERE scope_id = :id AND scope_kind = 'USER')")
                    .setParameter("id", userId).executeUpdate();
            entityManager.createNativeQuery("DELETE FROM billing_contracts WHERE scope_id = :id AND scope_kind = 'USER'")
                    .setParameter("id", userId).executeUpdate();
            entityManager.createNativeQuery("DELETE FROM billing_customers WHERE scope_id = :id AND scope_kind = 'USER'")
                    .setParameter("id", userId).executeUpdate();
        });
    }

    // ═════════ AC-104: PENDING_PAYMENT 中は pendingChange が乗る ═════════

    @Test
    @DisplayName("AC-104: PENDING_PAYMENT中の契約はGET /me/entitlementsの応答にpendingChange"
            + "(status=PENDING_PAYMENT・effectiveAt・paymentActionRequired=false)が乗る")
    void AC104_支払い待ち中はpendingChangeが乗りpaymentActionRequiredはfalse() throws Exception {
        UUID contractId = insertContract(ContractStatus.ACTIVE);
        Instant effectiveAt = Instant.now(clock).truncatedTo(ChronoUnit.SECONDS);
        givenPendingPlanChange(contractId, BillingContractChangeStatus.PENDING_PAYMENT, effectiveAt);

        MvcResult result = mockMvc.perform(get(ENTITLEMENTS_PATH).with(user(String.valueOf(userId))))
                .andExpect(status().isOk())
                .andReturn();

        JsonNode activePlan = new ObjectMapper()
                .readTree(result.getResponse().getContentAsString())
                .path("data").path("activePlan");
        JsonNode pendingChange = activePlan.path("pendingChange");
        assertThat(pendingChange.isMissingNode() || pendingChange.isNull())
                .as("AC-104: 支払い待ちであることは操作を試みる前にも見えなければならない。"
                        + "pendingChangeが投影に存在しないのは正本違反")
                .isFalse();
        assertThat(pendingChange.path("status").asText(null))
                .as("pendingChange.status がchange行のstatusをそのまま運ぶ")
                .isEqualTo("PENDING_PAYMENT");
        assertThat(pendingChange.path("effectiveAt").asText(null))
                .as("pendingChange.effectiveAt が存在する").isNotBlank();
        assertThat(pendingChange.path("paymentActionRequired").asBoolean(true))
                .as("PENDING_PAYMENT（3DS未着手）ではpaymentActionRequiredはfalse").isFalse();

        org.mockito.Mockito.verifyNoInteractions(billingPaymentGateway);
    }

    @Test
    @DisplayName("AC-104: REQUIRES_ACTION中はpendingChange.paymentActionRequired=trueになる"
            + "（3DS確認の導線をFEが出せる材料。AC-103の裏取り）")
    void AC104_3DS待ち中はpaymentActionRequiredがtrue() throws Exception {
        UUID contractId = insertContract(ContractStatus.ACTIVE);
        Instant effectiveAt = Instant.now(clock).truncatedTo(ChronoUnit.SECONDS);
        givenPendingPlanChange(contractId, BillingContractChangeStatus.REQUIRES_ACTION, effectiveAt);

        MvcResult result = mockMvc.perform(get(ENTITLEMENTS_PATH).with(user(String.valueOf(userId))))
                .andExpect(status().isOk())
                .andReturn();

        JsonNode pendingChange = new ObjectMapper()
                .readTree(result.getResponse().getContentAsString())
                .path("data").path("activePlan").path("pendingChange");
        assertThat(pendingChange.path("status").asText(null)).isEqualTo("REQUIRES_ACTION");
        assertThat(pendingChange.path("paymentActionRequired").asBoolean(false))
                .as("REQUIRES_ACTIONは3DS等の追加認証待ち。FEはこれを見て「確認を再開」導線を出す（AC-103）")
                .isTrue();
    }

    // ═════════ 陰性対照: 支払い待ちでない契約はpendingChangeが乗らない ═════════

    @Test
    @DisplayName("陰性対照: 支払い待ち中の変更が無い契約はpendingChangeがnull"
            + "（常時表示になっていないことの確認。AC-107の裏取り）")
    void 陰性対照_支払い待ちでない契約はpendingChangeがnull() throws Exception {
        insertContract(ContractStatus.ACTIVE);

        MvcResult result = mockMvc.perform(get(ENTITLEMENTS_PATH).with(user(String.valueOf(userId))))
                .andExpect(status().isOk())
                .andReturn();

        JsonNode activePlan = new ObjectMapper()
                .readTree(result.getResponse().getContentAsString())
                .path("data").path("activePlan");
        assertThat(activePlan.path("pendingChange").isNull()
                        || activePlan.path("pendingChange").isMissingNode())
                .as("支払い待ちの変更が無ければpendingChangeは出ない（常時表示化の防止）")
                .isTrue();
    }

    // ═════════ フィクスチャ ═════════

    private Long insertUser() {
        return transactionTemplate.execute(tx -> {
            com.mannschaft.app.auth.entity.UserEntity u = com.mannschaft.app.auth.entity.UserEntity.builder()
                    .email("pr6b1-pendingchange-" + System.nanoTime() + "@example.com")
                    .lastName("試練").firstName("待機").displayName("試練 待機")
                    .status(com.mannschaft.app.auth.entity.UserEntity.UserStatus.ACTIVE)
                    .locale("ja").timezone("Asia/Tokyo").isSearchable(true).build();
            entityManager.persist(u);
            entityManager.flush();
            return u.getId();
        });
    }

    private UUID insertContract(ContractStatus status) {
        return transactionTemplate.execute(tx -> {
            BillingContractEntity c = BillingContractEntity.builder()
                    .scopeKind(EntitlementScopeKind.USER).scopeId(userId)
                    .contractKind(ContractKind.PLAN).planKey(TO_PLAN_KEY)
                    .status(status)
                    .priceJpySnapshot(3_000)
                    .pspSubscriptionRef("sub_pr6b1_pendingchange_" + System.nanoTime())
                    .pspCustomerRef("cus_pr6b1_pendingchange_" + userId)
                    .currentPeriodEnd(LocalDateTime.now(clock).plusDays(15).withNano(0))
                    .contractedAt(LocalDateTime.now(clock).minusMonths(1))
                    .createdBy(userId).payerUserId(userId)
                    .version(0L)
                    .build();
            entityManager.persist(c);
            entityManager.flush();
            return c.getId();
        });
    }

    /**
     * PENDING_PAYMENT/REQUIRES_ACTION の変更行を、対応する operation・pointer とともに作る。
     *
     * <p>{@code billing_contract_changes.operation_id} は FK 相当の論理参照だが、テストプロファイルは
     * Entity から DDL を生成するため band version 等の参照先は実在させなくてもよい
     * （{@code BillingContractOperationRecoveryPlanChangeIT} と同じ前例）。</p>
     */
    private void givenPendingPlanChange(UUID contractId, BillingContractChangeStatus status, Instant effectiveAt) {
        UUID customerId = transactionTemplate.execute(tx -> {
            BillingCustomerEntity customer = BillingCustomerEntity.builder()
                    .scopeKind(EntitlementScopeKind.USER)
                    .scopeId(userId)
                    .pspCustomerRef("cus_pr6b1_pendingchange_op_" + userId + "_" + System.nanoTime())
                    .status("ACTIVE")
                    .provisionAttempts(0)
                    .version(0L)
                    .createdAt(Instant.now())
                    .updatedAt(Instant.now())
                    .build();
            entityManager.persist(customer);
            entityManager.flush();
            return customer.getId();
        });

        UUID operationId = transactionTemplate.execute(tx -> operationRepository.save(
                BillingContractOperationEntity.builder()
                        .contractId(contractId)
                        .billingCustomerId(customerId)
                        .kind(BillingOperationKind.PLAN_CHANGE)
                        .status(BillingOperationStatus.CALLING_STRIPE)
                        .step(BillingOperationStep.STRIPE_APPLY_PLAN_CHANGE)
                        .idempotencyKey(UUID.randomUUID().toString())
                        .requestHash(String.format("%064x",
                                new java.math.BigInteger(1, UUID.randomUUID().toString()
                                        .getBytes(java.nio.charset.StandardCharsets.UTF_8))).substring(0, 64))
                        .stripeSubscriptionRef("sub_pr6b1_pendingchange_" + contractId)
                        .version(0L)
                        .actorKind(BillingOperationActorKind.USER)
                        .createdBy(userId)
                        .build()).getId());

        transactionTemplate.executeWithoutResult(tx ->
                pointerRepository.save(ActiveBillingContractOperationPointerEntity.builder()
                        .contractId(contractId)
                        .operationId(operationId)
                        .build()));

        transactionTemplate.executeWithoutResult(tx -> changeRepository.save(
                BillingContractChangeEntity.builder()
                        .operationId(operationId)
                        .contractId(contractId)
                        .billingCustomerId(customerId)
                        .kind(BillingContractChangeKind.UPGRADE)
                        .status(status)
                        .fromPlanKey(FROM_PLAN_KEY)
                        .toPlanKey(TO_PLAN_KEY)
                        .fromPriceBandVersionId(UUID.randomUUID())
                        .toPriceBandVersionId(UUID.randomUUID())
                        .fromAmountIncludingTax(1_000L)
                        .toAmountIncludingTax(3_000L)
                        .stripeSubscriptionRef("sub_pr6b1_pendingchange_" + contractId)
                        .pendingUpdateExpiresAt(Instant.now(clock).plus(1, ChronoUnit.HOURS))
                        .effectiveAt(effectiveAt)
                        .idempotencyKey(operationId.toString())
                        .requestHash(String.format("%064x",
                                new java.math.BigInteger(1, UUID.randomUUID().toString()
                                        .getBytes(java.nio.charset.StandardCharsets.UTF_8))).substring(0, 64))
                        .version(0L)
                        .createdBy(userId)
                        .build()));
    }
}
