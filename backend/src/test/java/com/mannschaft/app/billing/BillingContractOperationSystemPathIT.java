package com.mannschaft.app.billing;

import com.mannschaft.app.auth.entity.UserEntity;
import com.mannschaft.app.billing.BillingContractOperationSagaService.OperationReservation;
import com.mannschaft.app.billing.BillingContractOperationSagaService.ReserveCommand;
import com.mannschaft.app.billing.api.BillingCustomerEntity;
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

/**
 * 試練A（第2隊）: AC-15 / AC-16 / AC-17 — D2（Stripe を伴わない経路は operation を作らない）と
 * D3（検疫は SYSTEM 経路を止めない。ただし孤児を作らない）を実 MySQL で測る。
 *
 * <p><b>空虚な緑への備え</b>: AC-15 の「operation を作らない」は、Saga がまだどの経路にも
 * 結線されていない現時点では<b>何もしていないから満たされる</b>。それだけでは検出力がゼロなので、
 * 同じ {@code @Nested} に<b>陽性対照</b>（有償解約は operation を作る）を対で置いている。
 * 対照が赤のまま AC-15 が緑なら、それは「まだ結線されていない」という状態を正しく表している。
 * 実装後に対照が緑になってはじめて AC-15 が意味を持つ。</p>
 *
 * <p>{@code @Transactional} を付けない理由は {@code BillingContractOperationSagaIT} と同じ
 * （Saga の commit 分割そのものが仕様であり、テストで包むと測れない）。</p>
 */
@EnabledIf("com.mannschaft.app.support.test.AbstractMySqlIntegrationTest#isDockerAvailable")
@DisplayName("試練A: AC-15/16/17 D2・D3 の SYSTEM 経路（実MySQL）")
class BillingContractOperationSystemPathIT extends AbstractMySqlIntegrationTest {

    private static final String REQUEST_HASH = "d".repeat(64);

    @Autowired private BillingContractOperationSagaService sagaService;
    @Autowired private BillingContractService billingContractService;
    @Autowired private BillingContractRepository billingContractRepository;
    @Autowired private BillingContractOperationRepository operationRepository;
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

    // ================================================================
    // AC-15 D2: Stripe を伴わない経路は operation を作らない
    // ================================================================

    @Nested
    @DisplayName("AC-15 D2: operation を作らない経路")
    class NoOperationPaths {

        @Test
        @DisplayName("AC-15: 無償契約の即時失効は operation を作らない")
        void freeContractCancelCreatesNoOperation() {
            UUID contractId = insertContract(null, null);

            billingContractService.cancelContract(
                    EntitlementScopeKind.USER, userId, contractId, userId);

            assertThat(reloadContract(contractId).getStatus()).isEqualTo(ContractStatus.CANCELLED);
            assertThat(operationCount(contractId)).isZero();
            assertThat(pointerCount(contractId)).isZero();
        }

        @Test
        @DisplayName("AC-15: 退会 purge の一括解約は operation を作らない")
        void purgeCreatesNoOperation() {
            UUID contractId = insertContract(1200, "sub_purge_" + userId);

            billingContractService.cancelAllUserContractsForPurge(userId);

            assertThat(reloadContract(contractId).getStatus()).isEqualTo(ContractStatus.CANCELLED);
            assertThat(operationCount(contractId)).isZero();
        }

        @Test
        @DisplayName("AC-15: webhook 由来の EXPIRED は operation を作らない")
        void webhookExpireCreatesNoOperation() {
            String subscriptionRef = "sub_expire_" + userId;
            UUID contractId = insertContract(1200, subscriptionRef);

            billingContractService.expireSubscriptionContract(
                    subscriptionRef, LocalDateTime.now(clock).truncatedTo(ChronoUnit.SECONDS));

            assertThat(reloadContract(contractId).getStatus()).isEqualTo(ContractStatus.EXPIRED);
            assertThat(operationCount(contractId)).isZero();
        }

        @Test
        @DisplayName("AC-15 陽性対照: 有償契約の期末解約は operation を作る（作らない側の3件が空虚な緑でないことの担保）")
        void paidCancelDoesCreateOperation() {
            UUID contractId = insertContract(1200, "sub_paid_" + userId);

            billingContractService.cancelContract(
                    EntitlementScopeKind.USER, userId, contractId, userId);

            assertThat(operationCount(contractId))
                    .as("有償解約が Saga に載っていれば operation が1件できるはずである")
                    .isEqualTo(1);
        }
    }

    // ================================================================
    // AC-16 / AC-17 D3: 検疫は SYSTEM 経路を止めない（孤児も作らない）
    // ================================================================

    @Nested
    @DisplayName("AC-16/AC-17 D3: 検疫貫通")
    class QuarantineBypass {

        @Test
        @DisplayName("AC-16: 検疫中でも退会 purge の即時解約は通り、非終端 operation が CANCELLED へ終端化されてから pointer が削除される")
        void purgePassesQuarantineAndLeavesNoOrphan() {
            UUID contractId = insertContract(1200, "sub_q_purge_" + userId);
            UUID operationId = quarantine(contractId);

            billingContractService.cancelAllUserContractsForPurge(userId);

            assertThat(reloadContract(contractId).getStatus())
                    .as("検疫より purge を優先する（GDPR）").isEqualTo(ContractStatus.CANCELLED);
            assertThat(reloadOperation(operationId).getStatus())
                    .as("貫通時は非終端 operation を CANCELLED へ終端化する")
                    .isEqualTo(BillingOperationStatus.CANCELLED);
            assertThat(pointerCount(contractId)).isZero();
            assertThat(nonTerminalOperationCount(contractId))
                    .as("孤児の非終端 operation を残してはならない").isZero();
        }

        @Test
        @DisplayName("AC-17: 検疫中でも customer.subscription.deleted による EXPIRED は通り、非終端 operation が CANCELLED へ終端化されてから pointer が削除される")
        void webhookExpirePassesQuarantineAndLeavesNoOrphan() {
            String subscriptionRef = "sub_q_expire_" + userId;
            UUID contractId = insertContract(1200, subscriptionRef);
            UUID operationId = quarantine(contractId);

            billingContractService.expireSubscriptionContract(
                    subscriptionRef, LocalDateTime.now(clock).truncatedTo(ChronoUnit.SECONDS));

            assertThat(reloadContract(contractId).getStatus()).isEqualTo(ContractStatus.EXPIRED);
            assertThat(reloadOperation(operationId).getStatus())
                    .isEqualTo(BillingOperationStatus.CANCELLED);
            assertThat(pointerCount(contractId)).isZero();
            assertThat(nonTerminalOperationCount(contractId)).isZero();
        }

        @Test
        @DisplayName("AC-16 対照: 検疫中の契約への利用者起点の解約は 409 のまま通らない（貫通するのは SYSTEM 経路だけである）")
        void userCancelStillBlockedDuringQuarantine() {
            UUID contractId = insertContract(1200, "sub_q_user_" + userId);
            quarantine(contractId);

            org.assertj.core.api.Assertions.assertThatThrownBy(() ->
                            billingContractService.cancelContract(
                                    EntitlementScopeKind.USER, userId, contractId, userId))
                    .isInstanceOf(com.mannschaft.app.common.BusinessException.class);

            assertThat(reloadContract(contractId).getStatus()).isEqualTo(ContractStatus.ACTIVE);
            assertThat(pointerCount(contractId)).as("利用者操作では検疫が解けない").isEqualTo(1);
        }
    }

    // ================================================================
    // フィクスチャ / DB 実読ヘルパ
    // ================================================================

    /** 契約を検疫（RECONCILIATION_REQUIRED ＋ pointer 保持）状態にし、その operationId を返す。 */
    private UUID quarantine(UUID contractId) {
        Long version = reloadContract(contractId).getVersion();
        OperationReservation reservation = sagaService.reserve(new ReserveCommand(
                contractId, BillingOperationKind.CANCEL, version,
                BillingOperationActorKind.USER, userId, REQUEST_HASH));
        sagaService.markCallingStripe(reservation.operationId());
        sagaService.quarantine(reservation.operationId(), "STRIPE_TIMEOUT");
        return reservation.operationId();
    }

    private Long insertUser() {
        UserEntity user = UserEntity.builder()
                .email("pr6a-systempath-" + System.nanoTime() + "@example.com")
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
                .pspCustomerRef("cus_sys_" + userId)
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

    private UUID insertContract(Integer priceJpy, String subscriptionRef) {
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

    private BillingContractOperationEntity reloadOperation(UUID operationId) {
        return transactionTemplate.execute(tx -> {
            entityManager.clear();
            return operationRepository.findByIdAndDeletedAtIsNull(operationId).orElseThrow();
        });
    }

    private long operationCount(UUID contractId) {
        return countBy("SELECT COUNT(*) FROM billing_contract_operations WHERE contract_id = :c",
                contractId);
    }

    private long nonTerminalOperationCount(UUID contractId) {
        return countBy("SELECT COUNT(*) FROM billing_contract_operations WHERE contract_id = :c"
                + " AND status IN ('CREATED','CALLING_STRIPE','RECONCILIATION_REQUIRED')", contractId);
    }

    private long pointerCount(UUID contractId) {
        return countBy("SELECT COUNT(*) FROM active_billing_contract_operation_pointers"
                + " WHERE contract_id = :c", contractId);
    }

    private long countBy(String sql, UUID contractId) {
        return transactionTemplate.execute(tx -> {
            entityManager.clear();
            Number count = (Number) entityManager.createNativeQuery(sql)
                    .setParameter("c", contractId).getSingleResult();
            return count.longValue();
        });
    }
}
