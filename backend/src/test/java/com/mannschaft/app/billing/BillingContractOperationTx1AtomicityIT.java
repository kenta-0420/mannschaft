package com.mannschaft.app.billing;

import com.mannschaft.app.billing.BillingContractOperationSagaService.OperationReservation;
import com.mannschaft.app.billing.BillingContractOperationSagaService.ReserveCommand;
import com.mannschaft.app.billing.api.BillingCustomerEntity;
import com.mannschaft.app.support.test.AbstractMySqlIntegrationTest;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.support.TransactionTemplate;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 試練A（第2隊）: AC-2 — tx1（operation INSERT ＋ pointer INSERT）の原子性を<b>実 MySQL の CHECK 制約</b>で測る。
 *
 * <p><b>なぜモック例外では測れないのか</b>: 「片方だけ残らない」という主張は、実際に commit と rollback が
 * 起きる環境でしか偽になりえない。Repository をモックして例外を投げさせても、そのテストが検証しているのは
 * 「例外が伝播したこと」だけで、DB に何が残ったかは一切測れない。したがってここでは
 * <b>pointer への INSERT だけを落とす CHECK 制約</b>をテスト実行中の実テーブルに張り、
 * 「operation は入ったが pointer が落ちた」という狙った途中失敗を DB に起こさせる。</p>
 *
 * <p><b>@Transactional を付けていないのは意図である</b>: テストをトランザクションで包むと commit が
 * 一度も起きず、tx1 が分裂していてもロールバックで巻き戻って緑になる（偽の緑）。また CHECK 制約の
 * 追加・削除（DDL）は MySQL で暗黙コミットを伴うため、JPA のトランザクションの外で
 * {@link DataSource} から直に実行する。</p>
 *
 * <p>{@code test} profile の schema は Entity 由来の自動生成で、V196 の CHECK 制約は再現されない
 * （{@code feedback_test_profile_ddl_create_skips_flyway_seed}）。本 IT が張る制約はそれとは別物の
 * 「テスト専用の障害注入器」であり、{@code chk_bco_*} の再現ではない。</p>
 */
@EnabledIf("com.mannschaft.app.support.test.AbstractMySqlIntegrationTest#isDockerAvailable")
@DisplayName("試練A: AC-2 tx1 の原子性（実MySQLのCHECK制約で途中失敗を起こす）")
class BillingContractOperationTx1AtomicityIT extends AbstractMySqlIntegrationTest {

    private static final String REQUEST_HASH = "b".repeat(64);
    private static final String PROBE_CONSTRAINT = "chk_tx1_atomicity_probe";

    @Autowired private BillingContractOperationSagaService sagaService;
    @Autowired private BillingContractRepository billingContractRepository;
    @Autowired private TransactionTemplate transactionTemplate;
    @Autowired private DataSource dataSource;
    @Autowired private Clock clock;
    @PersistenceContext private EntityManager entityManager;

    @MockitoBean private BillingPaymentGateway billingPaymentGateway;

    private Long scopeId;
    private UUID customerId;
    /** pointer INSERT が CHECK 制約で必ず落ちる契約。 */
    private UUID blockedContractId;
    /** 制約に引っかからない契約（陽性対照）。 */
    private UUID healthyContractId;

    @BeforeEach
    void setUp() {
        Mockito.reset(billingPaymentGateway);
        scopeId = Math.abs(System.nanoTime() % 1_000_000_000L) + 710_000_000L;
        transactionTemplate.executeWithoutResult(tx -> {
            customerId = insertCustomer();
            blockedContractId = insertContract("sub_blocked_");
            healthyContractId = insertContract("sub_healthy_");
        });
        // DDL は暗黙コミットを伴うため JPA のトランザクション外で実行する。
        executeDdl("ALTER TABLE active_billing_contract_operation_pointers"
                + " ADD CONSTRAINT " + PROBE_CONSTRAINT
                + " CHECK (HEX(contract_id) <> '" + hex(blockedContractId) + "')");
    }

    @AfterEach
    void tearDown() {
        executeDdlQuietly("ALTER TABLE active_billing_contract_operation_pointers"
                + " DROP CONSTRAINT " + PROBE_CONSTRAINT);
        transactionTemplate.executeWithoutResult(tx -> {
            entityManager.createNativeQuery(
                            "DELETE FROM active_billing_contract_operation_pointers "
                                    + "WHERE contract_id IN (SELECT id FROM billing_contracts WHERE scope_id = :s)")
                    .setParameter("s", scopeId).executeUpdate();
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
    @DisplayName("AC-2: pointer INSERT が落ちると operation も残らない（tx1 が同一トランザクションで巻き戻る）")
    void pointerInsertFailureRollsBackOperationInsert() {
        Long version = versionOf(blockedContractId);

        assertThatThrownBy(() -> sagaService.reserve(new ReserveCommand(
                blockedContractId, BillingOperationKind.CANCEL, version,
                BillingOperationActorKind.SYSTEM, null, REQUEST_HASH)))
                .isInstanceOf(RuntimeException.class);

        assertThat(operationCount(blockedContractId))
                .as("pointer が落ちたのに operation だけ残っている = tx1 が分裂している").isZero();
        assertThat(pointerCount(blockedContractId)).isZero();
    }

    @Test
    @DisplayName("AC-2: 障害注入していない契約では operation と pointer が両方 commit される（陽性対照・CHECK制約が全てを落としているのではない）")
    void healthyContractCommitsBothRows() {
        Long version = versionOf(healthyContractId);

        OperationReservation reservation = sagaService.reserve(new ReserveCommand(
                healthyContractId, BillingOperationKind.CANCEL, version,
                BillingOperationActorKind.SYSTEM, null, REQUEST_HASH));

        assertThat(reservation.operationId()).isNotNull();
        assertThat(operationCount(healthyContractId)).isEqualTo(1);
        assertThat(pointerCount(healthyContractId)).isEqualTo(1);
    }

    @Test
    @DisplayName("AC-2: 障害注入した契約の pointer INSERT は実際に CHECK 制約で落ちる（測定器そのものの健全性）")
    void probeConstraintActuallyRejectsPointerInsert() {
        assertThatThrownBy(() -> transactionTemplate.executeWithoutResult(tx ->
                entityManager.createNativeQuery(
                                "INSERT INTO active_billing_contract_operation_pointers"
                                        + " (contract_id, operation_id, created_at, updated_at)"
                                        + " VALUES (UNHEX(:c), UNHEX(:o), NOW(6), NOW(6))")
                        .setParameter("c", hex(blockedContractId))
                        .setParameter("o", hex(UUID.randomUUID()))
                        .executeUpdate()))
                .isInstanceOf(RuntimeException.class);
        assertThat(pointerCount(blockedContractId)).isZero();
    }

    // ================================================================
    // ヘルパ
    // ================================================================

    private static String hex(UUID id) {
        return id.toString().replace("-", "").toUpperCase(java.util.Locale.ROOT);
    }

    private void executeDdl(String ddl) {
        try (Connection connection = dataSource.getConnection();
             Statement statement = connection.createStatement()) {
            statement.execute(ddl);
        } catch (SQLException e) {
            throw new IllegalStateException("障害注入用 DDL の実行に失敗した: " + ddl, e);
        }
    }

    private void executeDdlQuietly(String ddl) {
        try (Connection connection = dataSource.getConnection();
             Statement statement = connection.createStatement()) {
            statement.execute(ddl);
        } catch (SQLException ignored) {
            // 後片付けのみ。制約が張られていない場合（setUp が落ちた等）は握って良い。
        }
    }

    private Long versionOf(UUID contractId) {
        return transactionTemplate.execute(tx -> {
            entityManager.clear();
            return billingContractRepository.findByIdAndDeletedAtIsNull(contractId)
                    .orElseThrow().getVersion();
        });
    }

    private UUID insertCustomer() {
        BillingCustomerEntity customer = BillingCustomerEntity.builder()
                .scopeKind(EntitlementScopeKind.TEAM)
                .scopeId(scopeId)
                .pspCustomerRef("cus_tx1_" + scopeId)
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

    private UUID insertContract(String refPrefix) {
        return billingContractRepository.saveAndFlush(BillingContractEntity.builder()
                .scopeKind(EntitlementScopeKind.TEAM)
                .scopeId(scopeId)
                .contractKind(ContractKind.PLAN)
                .planKey("FULL")
                .status(ContractStatus.ACTIVE)
                .priceJpySnapshot(1200)
                .billingCustomerId(customerId)
                .contractedAt(LocalDateTime.now(clock).minusDays(10))
                .currentPeriodEnd(LocalDateTime.now(clock).plusDays(20).truncatedTo(ChronoUnit.SECONDS))
                .pspSubscriptionRef(refPrefix + scopeId)
                .build()).getId();
    }

    private long operationCount(UUID contractId) {
        return transactionTemplate.execute(tx -> {
            entityManager.clear();
            Number count = (Number) entityManager.createNativeQuery(
                            "SELECT COUNT(*) FROM billing_contract_operations WHERE contract_id = :c")
                    .setParameter("c", contractId).getSingleResult();
            return count.longValue();
        });
    }

    private long pointerCount(UUID contractId) {
        return transactionTemplate.execute(tx -> {
            entityManager.clear();
            Number count = (Number) entityManager.createNativeQuery(
                            "SELECT COUNT(*) FROM active_billing_contract_operation_pointers"
                                    + " WHERE contract_id = :c")
                    .setParameter("c", contractId).getSingleResult();
            return count.longValue();
        });
    }
}
