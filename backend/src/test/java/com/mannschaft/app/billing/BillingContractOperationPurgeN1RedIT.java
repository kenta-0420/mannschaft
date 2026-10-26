package com.mannschaft.app.billing;

import com.mannschaft.app.auth.entity.UserEntity;
import com.mannschaft.app.common.visibility.perf.SqlIntentCounter;
import com.mannschaft.app.support.test.AbstractMySqlIntegrationTest;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Billing Center PR6a — F群 性能（AC-72・AC-72b）の受け入れテスト（試練C・red）。
 *
 * <p><b>AC-72</b>: entitlements の valid_until 更新（解約に伴う半開区間の反映）がループ内クエリに
 * ならないこと。<b>AC-72b</b>: 退会 purge の一括経路（契約数 M をループし契約ごとに save・pointer
 * 削除・entitlement 検索/revoke を行う {@code BillingContractService.cancelAllUserContractsForPurge}）に
 * operation の終端化を足しても、M に比例した追加クエリが増えないこと。
 *
 * <p><b>計測手法</b>: {@link SqlIntentCounter}（テーブル名ベースの意図単位カウンタ）。
 * 絶対上限だけでは N+1 を捕捉できないため、契約数の異なる2走査で本数が一致することを併せて測る
 * （このリポジトリの定石。{@code feedback_sql_count_guard_never_ran_main_test_yml_shadowed} の教訓により
 * ガード自身が実際に走っていることも {@link #陽性対照_SqlIntentCounterは実際に計測できている()} で確かめる）。</p>
 */
@EnabledIf("com.mannschaft.app.support.test.AbstractMySqlIntegrationTest#isDockerAvailable")
@DisplayName("PR6a purge一括のN+1固定（F群 AC-72・AC-72b・試練C red）")
class BillingContractOperationPurgeN1RedIT extends AbstractMySqlIntegrationTest {

    private static final String PLAN_KEY = "FULL";
    private static final String T_CONTRACTS = "billing_contracts";
    private static final String T_ENTITLEMENTS = "entitlements";
    private static final String T_POINTERS = "active_billing_contract_operation_pointers";
    private static final String T_OPERATIONS = "billing_contract_operations";

    @Autowired private BillingContractService billingContractService;
    @Autowired private TransactionTemplate transactionTemplate;
    @Autowired private Clock clock;
    @PersistenceContext private EntityManager entityManager;

    private Long lightUserId;
    private Long heavyUserId;

    @BeforeEach
    void setUp() {
        SqlIntentCounter.reset();
        lightUserId = insertUser("light");
        heavyUserId = insertUser("heavy");
    }

    @AfterEach
    void tearDown() {
        cleanupScope(lightUserId);
        cleanupScope(heavyUserId);
    }

    // ═════════ AC-72b: purge一括のSQL本数がMに依存しない ═════════

    @Test
    @DisplayName("AC-72b: 退会purgeの一括解約は契約数M=2でもM=12でも業務SQL本数が一致する（N+1なら比例して増える）")
    void AC72b_purge一括のSQLはMに依存しない() {
        insertContracts(lightUserId, 2);
        insertContracts(heavyUserId, 12);

        SqlIntentCounter.reset();
        billingContractService.cancelAllUserContractsForPurge(lightUserId);
        int lightSql = businessIntents();

        SqlIntentCounter.reset();
        billingContractService.cancelAllUserContractsForPurge(heavyUserId);
        int heavySql = businessIntents();

        assertThat(heavySql)
                .as("2件と12件で業務SQL本数が一致すること。捕捉=%s", SqlIntentCounter.capturedSqls())
                .isEqualTo(lightSql);
    }

    @Test
    @DisplayName("AC-72b: purge対象0件（無契約ユーザー）でも異常なく完了しSQLがほぼ発生しない")
    void AC72b_purge対象ゼロ件でも正常完了() {
        Long emptyUser = insertUser("empty");

        List<String> refs = billingContractService.cancelAllUserContractsForPurge(emptyUser);

        assertThat(refs).isEmpty();
        cleanupScope(emptyUser);
    }

    // ═════════ AC-72: entitlementsのvalid_until更新がループ内クエリにならない ═════════

    @Test
    @DisplayName("AC-72: 1契約に紐づくentitlementsが1件でも5件でもcancel経路のentitlements書込SQL本数が一致する")
    void AC72_entitlements書込はループ内クエリにならない() {
        UUID fewContract = insertSingleContract(lightUserId, 1);
        insertEntitlements(lightUserId, fewContract, 1);
        UUID manyContract = insertSingleContract(heavyUserId, 1);
        insertEntitlements(heavyUserId, manyContract, 5);

        SqlIntentCounter.reset();
        billingContractService.cancelAllUserContractsForPurge(lightUserId);
        int fewSql = SqlIntentCounter.intentCount(T_ENTITLEMENTS);

        SqlIntentCounter.reset();
        billingContractService.cancelAllUserContractsForPurge(heavyUserId);
        int manySql = SqlIntentCounter.intentCount(T_ENTITLEMENTS);

        assertThat(manySql)
                .as("entitlements 1件と5件で書込SQL本数が一致すること（N+1なら比例して増える）。捕捉=%s",
                        SqlIntentCounter.capturedSqls())
                .isEqualTo(fewSql);
    }

    // ═════════ 陽性対照: カウンタ自体が実際に計測できている ═════════

    @Test
    @DisplayName("陽性対照: SqlIntentCounterはbilling_contractsへのSQLを実際に検出できる（ガード自体が空振りしていないことの証明）")
    void 陽性対照_SqlIntentCounterは実際に計測できている() {
        insertContracts(lightUserId, 2);

        SqlIntentCounter.reset();
        billingContractService.cancelAllUserContractsForPurge(lightUserId);

        assertThat(SqlIntentCounter.intentCount(T_CONTRACTS))
                .as("billing_contracts への書込SQLが1本以上捕捉されること（0ならガードが空振り＝偽陰性の温床）")
                .isGreaterThan(0);
    }

    // ═════════ フィクスチャ ═════════

    private int businessIntents() {
        return SqlIntentCounter.intentCount(T_CONTRACTS)
                + SqlIntentCounter.intentCount(T_ENTITLEMENTS)
                + SqlIntentCounter.intentCount(T_POINTERS)
                + SqlIntentCounter.intentCount(T_OPERATIONS);
    }

    private Long insertUser(String suffix) {
        return transactionTemplate.execute(tx -> {
            UserEntity u = UserEntity.builder()
                    .email("pr6a-purge-" + suffix + "-" + System.nanoTime() + "@example.com")
                    .lastName("試練").firstName(suffix).displayName("試練 purge " + suffix)
                    .status(UserEntity.UserStatus.ACTIVE).locale("ja").timezone("Asia/Tokyo")
                    .isSearchable(true).build();
            entityManager.persist(u);
            entityManager.flush();
            return u.getId();
        });
    }

    /** 無償契約をN件作る（purgeが即時解約する対象。ADDONに分けてスロット重複を避ける）。 */
    private void insertContracts(Long userId, int count) {
        transactionTemplate.executeWithoutResult(tx -> {
            for (int i = 0; i < count; i++) {
                BillingContractEntity c = BillingContractEntity.builder()
                        .scopeKind(EntitlementScopeKind.USER).scopeId(userId)
                        .contractKind(ContractKind.ADDON).featureKey("feature." + i)
                        .status(ContractStatus.ACTIVE)
                        .priceJpySnapshot(null)
                        .contractedAt(LocalDateTime.now(clock).minusMonths(1))
                        .createdBy(userId).payerUserId(userId)
                        .version(0L)
                        .build();
                entityManager.persist(c);
            }
            entityManager.flush();
        });
    }

    private UUID insertSingleContract(Long userId, int index) {
        return transactionTemplate.execute(tx -> {
            BillingContractEntity c = BillingContractEntity.builder()
                    .scopeKind(EntitlementScopeKind.USER).scopeId(userId)
                    .contractKind(ContractKind.PLAN).planKey(PLAN_KEY)
                    .status(ContractStatus.ACTIVE)
                    .priceJpySnapshot(null)
                    .contractedAt(LocalDateTime.now(clock).minusMonths(1))
                    .createdBy(userId).payerUserId(userId)
                    .version(0L)
                    .build();
            entityManager.persist(c);
            entityManager.flush();
            return c.getId();
        });
    }

    private void insertEntitlements(Long userId, UUID contractId, int count) {
        transactionTemplate.executeWithoutResult(tx -> {
            for (int i = 0; i < count; i++) {
                EntitlementEntity e = EntitlementEntity.builder()
                        .scopeKind(EntitlementScopeKind.USER).scopeId(userId)
                        .featureKey("feature.child." + i)
                        .sourceKind(EntitlementSourceKind.PLAN).sourceRefId(contractId)
                        .validFrom(LocalDateTime.now(clock).minusMonths(1))
                        .validUntil(null)
                        .build();
                entityManager.persist(e);
            }
            entityManager.flush();
        });
    }

    private void cleanupScope(Long userId) {
        transactionTemplate.executeWithoutResult(tx -> {
            entityManager.createNativeQuery("DELETE FROM entitlements WHERE scope_id = :id")
                    .setParameter("id", userId).executeUpdate();
            entityManager.createNativeQuery(
                            "DELETE FROM active_billing_contract_operation_pointers WHERE contract_id IN "
                                    + "(SELECT id FROM billing_contracts WHERE scope_id = :id)")
                    .setParameter("id", userId).executeUpdate();
            entityManager.createNativeQuery(
                            "DELETE FROM billing_contract_operations WHERE contract_id IN "
                                    + "(SELECT id FROM billing_contracts WHERE scope_id = :id)")
                    .setParameter("id", userId).executeUpdate();
            entityManager.createNativeQuery("DELETE FROM billing_contracts WHERE scope_id = :id")
                    .setParameter("id", userId).executeUpdate();
        });
    }
}
