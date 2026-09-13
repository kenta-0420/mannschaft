package com.mannschaft.app.billing;

import com.mannschaft.app.auth.entity.UserEntity;
import com.mannschaft.app.billing.api.BillingCustomerEntity;
import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.support.test.AbstractMySqlIntegrationTest;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 試練A（第2隊）: AC-20 / AC-21 — <b>旧経路の締め</b>を実 MySQL で測る。
 *
 * <p>新設の cancel / 撤回 API だけを Saga に載せても、旧 API（{@code DELETE /…/contracts/{id}} と
 * {@code PUT /…/contracts/{id}}）が pointer を無視したままなら、進行中の Saga の脇から契約を
 * 書き換えられてしまい排他が成立しない。ここではその2経路が pointer と {@code cancelled_at} を
 * 尊重することを、<b>例外のコードと契約行の実値</b>で測る。</p>
 *
 * <p><b>フィクスチャは Saga Service を使わない</b>: operation 行と pointer 行を Repository で直に
 * 作る。こうしておくと本 IT の赤は「旧経路が pointer を見ていない」という AC-20/21 固有の理由だけに
 * 由来し、第5隊の Saga 未実装に巻き込まれて赤くなることがない。</p>
 *
 * <p>AC-21 の契約は<b>わざと無償（{@code psp_subscription_ref} が NULL）</b>にしてある。有償だと
 * 既存 {@code changePlan} の「有償は 409」ガード（{@code CONTRACT_CHANGE_REQUIRES_PAYMENT}）が
 * 先に効いてしまい、{@code cancelled_at} を見ているかどうかを一切測れない（別の理由で緑になる）。</p>
 */
@EnabledIf("com.mannschaft.app.support.test.AbstractMySqlIntegrationTest#isDockerAvailable")
@DisplayName("試練A: AC-20/21 旧経路（DELETE / PUT）の締め")
class BillingContractLegacyMutationGuardIT extends AbstractMySqlIntegrationTest {

    @Autowired private BillingContractService billingContractService;
    @Autowired private BillingContractRepository billingContractRepository;
    @Autowired private BillingContractOperationRepository operationRepository;
    @Autowired private ActiveBillingContractOperationPointerRepository pointerRepository;
    @Autowired private TransactionTemplate transactionTemplate;
    @Autowired private Clock clock;
    @PersistenceContext private EntityManager entityManager;

    @MockitoBean private BillingPaymentGateway billingPaymentGateway;

    private Long userId;
    private UUID customerId;

    @BeforeEach
    void setUp() {
        Mockito.reset(billingPaymentGateway);
        transactionTemplate.executeWithoutResult(tx -> {
            userId = insertUser();
            customerId = insertCustomer();
        });
    }

    @AfterEach
    void tearDown() {
        transactionTemplate.executeWithoutResult(tx -> {
            entityManager.createNativeQuery(
                            "DELETE FROM active_billing_contract_operation_pointers "
                                    + "WHERE contract_id IN (SELECT id FROM billing_contracts WHERE scope_id = :s)")
                    .setParameter("s", userId).executeUpdate();
            entityManager.createNativeQuery(
                            "DELETE FROM billing_contract_operations "
                                    + "WHERE contract_id IN (SELECT id FROM billing_contracts WHERE scope_id = :s)")
                    .setParameter("s", userId).executeUpdate();
            entityManager.createNativeQuery(
                            "DELETE FROM entitlements WHERE scope_kind = 'USER' AND scope_id = :s")
                    .setParameter("s", userId).executeUpdate();
            entityManager.createNativeQuery(
                            "DELETE FROM active_contract_pointers WHERE scope_kind = 'USER' AND scope_id = :s")
                    .setParameter("s", userId).executeUpdate();
            entityManager.createNativeQuery("DELETE FROM billing_contracts WHERE scope_id = :s")
                    .setParameter("s", userId).executeUpdate();
            entityManager.createNativeQuery("DELETE FROM billing_customers WHERE scope_id = :s")
                    .setParameter("s", userId).executeUpdate();
            entityManager.createNativeQuery("DELETE FROM users WHERE id = :s")
                    .setParameter("s", userId).executeUpdate();
        });
    }

    @Nested
    @DisplayName("AC-20 旧経路も pointer を尊重する")
    class LegacyPathsRespectPointer {

        @Test
        @DisplayName("AC-20: pointer 在りの契約への旧 DELETE（cancelContract）は 409 で、契約は書き換わらない")
        void legacyCancelIsRejectedWhilePointerExists() {
            UUID contractId = insertContract(null, null, null);
            insertOperationWithPointer(contractId, BillingOperationStatus.CALLING_STRIPE);

            assertThatThrownBy(() -> billingContractService.cancelContract(
                    EntitlementScopeKind.USER, userId, contractId, userId))
                    .isInstanceOf(BusinessException.class)
                    .extracting(e -> ((BusinessException) e).getErrorCode().getCode())
                    .isEqualTo(EntitlementErrorCode.CHANGE_CONFLICT.getCode());

            BillingContractEntity contract = reloadContract(contractId);
            assertThat(contract.getStatus()).isEqualTo(ContractStatus.ACTIVE);
            assertThat(contract.getCancelledAt()).isNull();
        }

        @Test
        @DisplayName("AC-20: pointer 在りの契約への旧 PUT（changePlan）は 409 で、契約は書き換わらない")
        void legacyChangePlanIsRejectedWhilePointerExists() {
            UUID contractId = insertContract(null, null, null);
            insertOperationWithPointer(contractId, BillingOperationStatus.CALLING_STRIPE);

            assertThatThrownBy(() -> billingContractService.changePlan(
                    EntitlementScopeKind.USER, userId, contractId, "LIGHT", userId))
                    .isInstanceOf(BusinessException.class)
                    .extracting(e -> ((BusinessException) e).getErrorCode().getCode())
                    .isEqualTo(EntitlementErrorCode.CHANGE_CONFLICT.getCode());

            BillingContractEntity contract = reloadContract(contractId);
            assertThat(contract.getStatus()).isEqualTo(ContractStatus.ACTIVE);
            assertThat(contract.getPlanKey()).isEqualTo("FULL");
        }

        @Test
        @DisplayName("AC-20 陽性対照: pointer が無い契約の旧 DELETE は従来どおり通る（恒久的に塞いだのではない）")
        void legacyCancelStillWorksWithoutPointer() {
            UUID contractId = insertContract(null, null, null);

            billingContractService.cancelContract(
                    EntitlementScopeKind.USER, userId, contractId, userId);

            assertThat(reloadContract(contractId).getStatus()).isEqualTo(ContractStatus.CANCELLED);
        }
    }

    @Nested
    @DisplayName("AC-21 解約予約中の契約はプラン変更できない")
    class CancelledAtBlocksChangePlan {

        @Test
        @DisplayName("AC-21: cancelled_at 非 NULL（解約予約中）の契約への changePlan は 409（撤回するまで通らない）")
        void changePlanIsRejectedWhileCancellationScheduled() {
            UUID contractId = insertContract(null, null,
                    LocalDateTime.now(clock).truncatedTo(ChronoUnit.SECONDS));

            assertThatThrownBy(() -> billingContractService.changePlan(
                    EntitlementScopeKind.USER, userId, contractId, "LIGHT", userId))
                    .isInstanceOf(BusinessException.class)
                    .extracting(e -> ((BusinessException) e).getErrorCode().getCode())
                    .as("cancelled_at を見ずにプラン変更を通してはならない")
                    .isEqualTo(EntitlementErrorCode.CHANGE_CONFLICT.getCode());

            BillingContractEntity contract = reloadContract(contractId);
            assertThat(contract.getPlanKey()).isEqualTo("FULL");
            assertThat(contract.getStatus()).isEqualTo(ContractStatus.ACTIVE);
        }
    }

    // ================================================================
    // フィクスチャ / DB 実読ヘルパ
    // ================================================================

    private void insertOperationWithPointer(UUID contractId, BillingOperationStatus status) {
        transactionTemplate.executeWithoutResult(tx -> {
            UUID operationId = operationRepository.saveAndFlush(
                    BillingContractOperationEntity.builder()
                            .contractId(contractId)
                            .billingCustomerId(customerId)
                            .kind(BillingOperationKind.CANCEL)
                            .status(status)
                            .step(BillingOperationStep.STRIPE_CANCEL_SUBSCRIPTION)
                            .idempotencyKey(UUID.randomUUID().toString())
                            .requestHash("e".repeat(64))
                            .version(0L)
                            .actorKind(BillingOperationActorKind.USER)
                            .createdBy(userId)
                            .build()).getId();
            pointerRepository.saveAndFlush(ActiveBillingContractOperationPointerEntity.builder()
                    .contractId(contractId)
                    .operationId(operationId)
                    .build());
        });
    }

    private Long insertUser() {
        UserEntity user = UserEntity.builder()
                .email("pr6a-legacy-" + System.nanoTime() + "@example.com")
                .lastName("試練").firstName("A").displayName("試練 A")
                .status(UserEntity.UserStatus.ACTIVE).locale("ja").timezone("Asia/Tokyo")
                .isSearchable(true).build();
        entityManager.persist(user);
        entityManager.flush();
        return user.getId();
    }

    private UUID insertCustomer() {
        BillingCustomerEntity customer = BillingCustomerEntity.builder()
                .scopeKind(EntitlementScopeKind.USER)
                .scopeId(userId)
                .pspCustomerRef("cus_legacy_" + userId)
                .status("ACTIVE")
                .provisionAttempts(0)
                .version(0L)
                .createdAt(Instant.now())
                .updatedAt(Instant.now())
                .build();
        entityManager.persist(customer);
        entityManager.flush();
        return customer.getId();
    }

    private UUID insertContract(Integer priceJpy, String subscriptionRef, LocalDateTime cancelledAt) {
        return transactionTemplate.execute(tx -> billingContractRepository.saveAndFlush(
                BillingContractEntity.builder()
                        .scopeKind(EntitlementScopeKind.USER)
                        .scopeId(userId)
                        .contractKind(ContractKind.PLAN)
                        .planKey("FULL")
                        .status(ContractStatus.ACTIVE)
                        .priceJpySnapshot(priceJpy)
                        .billingCustomerId(customerId)
                        .contractedAt(LocalDateTime.now(clock).minusDays(10))
                        .currentPeriodEnd(LocalDateTime.now(clock).plusDays(20)
                                .truncatedTo(ChronoUnit.SECONDS))
                        .cancelledAt(cancelledAt)
                        .createdBy(userId)
                        .payerUserId(userId)
                        .pspSubscriptionRef(subscriptionRef)
                        .build()).getId());
    }

    private BillingContractEntity reloadContract(UUID contractId) {
        return transactionTemplate.execute(tx -> {
            entityManager.clear();
            return billingContractRepository.findByIdAndDeletedAtIsNull(contractId).orElseThrow();
        });
    }
}
