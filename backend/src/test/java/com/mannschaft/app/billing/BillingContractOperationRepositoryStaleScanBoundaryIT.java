package com.mannschaft.app.billing;

import com.mannschaft.app.billing.api.BillingCustomerEntity;
import com.mannschaft.app.support.test.AbstractMySqlIntegrationTest;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 第11隊是正（AC-90）: {@code BillingContractOperationRepository
 * #findByStatusInAndDeletedAtIsNullAndUpdatedAtLessThan} が真に半開区間（{@code updated_at < staleBefore}）
 * であることを、<b>SQL/JPQL の述語を直接呼んで</b>決定的に固定する。
 *
 * <h2>なぜ {@code BillingContractOperationRecoveryPlanChangeIT} の元テストでは測れなかったか</h2>
 * <p>元の {@code exactlyFiveMinutesIsNotScanned} はフィクスチャの {@code updated_at} を
 * 「実時計の {@code now} から5分引いて秒に切り捨てた値」で作り、後から
 * {@code BillingContractOperationRecoveryService.recoverStaleOperations()} が<b>別の瞬間</b>に
 * {@code Instant.now(clock).minus(5分)} を計算していた。フィクスチャ側の切り捨てで最大1秒過去へ
 * 倒れるうえ、サービス側の走査時刻は必ずフィクスチャ作成より後なので、この行は<b>実際には
 * 5分を超えて</b>おり、境界（ちょうど5分）を検体として表現できていなかった（非決定的）。</p>
 *
 * <h2>是正の形</h2>
 * <p>本クラスはサービスの2段階計算を経由せず、{@code updated_at = T} の行を1件作り、
 * {@code staleBefore} を<b>直接同じ値 T</b>・<b>T+1秒</b>で呼び分けて、
 * 半開区間（{@code <} であって {@code <=} ではない）を1本のクエリで決定的に固定する。</p>
 */
@EnabledIf("com.mannschaft.app.support.test.AbstractMySqlIntegrationTest#isDockerAvailable")
@DisplayName("第11隊是正: stale 走査クエリの半開区間（実MySQL・AC-90）")
class BillingContractOperationRepositoryStaleScanBoundaryIT extends AbstractMySqlIntegrationTest {

    private static final Set<BillingOperationStatus> SCAN_STATUSES =
            Set.of(BillingOperationStatus.CREATED, BillingOperationStatus.CALLING_STRIPE);

    @Autowired private BillingContractRepository billingContractRepository;
    @Autowired private BillingContractOperationRepository operationRepository;
    @Autowired private TransactionTemplate transactionTemplate;
    @PersistenceContext private EntityManager entityManager;

    private Long scopeId;
    private UUID customerId;
    private UUID contractId;
    private UUID operationId;
    /** 検体の唯一の updated_at（境界値そのもの）。 */
    private Instant exactBoundary;

    @BeforeEach
    void setUp() {
        scopeId = Math.abs(System.nanoTime() % 1_000_000_000L) + 851_000_000L;
        exactBoundary = Instant.now().minusSeconds(3_600).truncatedTo(ChronoUnit.SECONDS);
        transactionTemplate.executeWithoutResult(tx -> {
            customerId = insertCustomer();
            contractId = insertContract();
            operationId = insertOperationAtExactUpdatedAt(exactBoundary);
        });
    }

    @AfterEach
    void tearDown() {
        transactionTemplate.executeWithoutResult(tx -> {
            entityManager.createNativeQuery(
                            "DELETE FROM billing_contract_operations "
                                    + "WHERE contract_id IN (SELECT id FROM billing_contracts WHERE scope_id = :s)")
                    .setParameter("s", scopeId).executeUpdate();
            entityManager.createNativeQuery("DELETE FROM billing_contracts WHERE scope_id = :s")
                    .setParameter("s", scopeId).executeUpdate();
            entityManager.createNativeQuery("DELETE FROM billing_customers WHERE scope_id = :s")
                    .setParameter("s", scopeId).executeUpdate();
        });
    }

    @Test
    @DisplayName("AC-90: staleBefore が updated_at とちょうど同値なら0件（半開区間・<= ではない）")
    void staleBeforeEqualToUpdatedAtScansNothing() {
        List<BillingContractOperationEntity> hits = scan(exactBoundary);

        assertThat(hits).as("updated_at = staleBefore は stale ではない").isEmpty();
    }

    @Test
    @DisplayName("AC-90: staleBefore が updated_at の1秒後なら1件（境界の1秒後は確実に stale）")
    void staleBeforeOneSecondAfterUpdatedAtScansTheRow() {
        List<BillingContractOperationEntity> hits = scan(exactBoundary.plusSeconds(1));

        assertThat(hits).extracting(BillingContractOperationEntity::getId)
                .as("updated_at < staleBefore の行は拾われる")
                .containsExactly(operationId);
    }

    private List<BillingContractOperationEntity> scan(Instant staleBefore) {
        return transactionTemplate.execute(tx -> {
            entityManager.clear();
            return operationRepository.findByStatusInAndDeletedAtIsNullAndUpdatedAtLessThan(
                    SCAN_STATUSES, staleBefore,
                    PageRequest.of(0, 200, Sort.by(Sort.Direction.ASC, "updatedAt")));
        });
    }

    private UUID insertCustomer() {
        BillingCustomerEntity customer = BillingCustomerEntity.builder()
                .scopeKind(EntitlementScopeKind.TEAM)
                .scopeId(scopeId)
                .pspCustomerRef("cus_scan_boundary_" + scopeId)
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

    private UUID insertContract() {
        String subscriptionRef = "sub_scan_boundary_" + scopeId;
        return billingContractRepository.save(BillingContractEntity.builder()
                .scopeKind(EntitlementScopeKind.TEAM)
                .scopeId(scopeId)
                .contractKind(ContractKind.PLAN)
                .planKey("BASIC")
                .status(ContractStatus.ACTIVE)
                .priceJpySnapshot(1200)
                .billingCustomerId(customerId)
                .contractedAt(LocalDateTime.now().minusDays(10))
                .currentPeriodEnd(LocalDateTime.now().plusDays(20).truncatedTo(ChronoUnit.SECONDS))
                .createdBy(scopeId)
                .payerUserId(scopeId)
                .pspSubscriptionRef(subscriptionRef)
                .build()).getId();
    }

    /** 行を作った直後に updated_at を境界値へ<b>一括 UPDATE で強制する</b>（{@code @PreUpdate} を経由しない）。 */
    private UUID insertOperationAtExactUpdatedAt(Instant updatedAt) {
        BillingContractOperationEntity operation = operationRepository.save(
                BillingContractOperationEntity.builder()
                        .contractId(contractId)
                        .billingCustomerId(customerId)
                        .kind(BillingOperationKind.PLAN_CHANGE)
                        .status(BillingOperationStatus.CALLING_STRIPE)
                        .step(BillingOperationStep.STRIPE_APPLY_PLAN_CHANGE)
                        .idempotencyKey(UUID.randomUUID().toString())
                        .requestHash("0".repeat(64))
                        .version(0L)
                        .actorKind(BillingOperationActorKind.USER)
                        .createdBy(scopeId)
                        .build());
        entityManager.flush();
        entityManager.createNativeQuery(
                        "UPDATE billing_contract_operations SET updated_at = :ts WHERE id = :id")
                .setParameter("ts", updatedAt)
                .setParameter("id", operation.getId())
                .executeUpdate();
        return operation.getId();
    }
}
