package com.mannschaft.app.payment.recovery;

import com.mannschaft.app.support.test.AbstractMySqlIntegrationTest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * F22.1 §6.3 第一陣: {@link FeeRecoveryBalanceRepository} の永続・制約・TenantAware メソッドを検証する。
 *
 * <p>{@code @SpringBootTest}（{@link AbstractMySqlIntegrationTest} 経由）で全コンテキストを起動するため、
 * {@code AbstractTenantAwareRepository} 継承による派生クエリ（{@code findByOrganizationIdAndDeletedAtIsNull}
 * 等）が起動時に解決できることも併せて担保する（解決不能なら ApplicationContext がロードできず全 SpringBootTest
 * が巻き添えになる既知のリスクを起動段階で検知する）。</p>
 *
 * <p>Docker 未起動環境では {@code @EnabledIf} によりスキップされる。</p>
 */
@DisplayName("FeeRecoveryBalanceRepository 永続・制約・TenantAware 統合テスト")
@EnabledIf("com.mannschaft.app.support.test.AbstractMySqlIntegrationTest#isDockerAvailable")
class FeeRecoveryBalanceRepositoryTest extends AbstractMySqlIntegrationTest {

    @Autowired
    private FeeRecoveryBalanceRepository repository;

    @Autowired
    private PlatformTransactionManager transactionManager;

    private static FeeRecoveryBalanceEntity newBalance(UUID connectAccountId, Long orgId,
                                                        long outstanding, String currency) {
        return FeeRecoveryBalanceEntity.builder()
                .connectAccountId(connectAccountId)
                .organizationId(orgId)
                .outstandingAmount(outstanding)
                .currency(currency)
                .build();
    }

    @Test
    @DisplayName("save/findById で残高を永続・復元でき UUIDv7 が採番される")
    void save_findById_永続復元できる() {
        UUID accountId = UUID.randomUUID();
        FeeRecoveryBalanceEntity saved = repository.saveAndFlush(
                newBalance(accountId, 1101L, 369L, "jpy"));

        assertThat(saved.getId()).as("UUIDv7 が採番されること").isNotNull();

        Optional<FeeRecoveryBalanceEntity> found = repository.findById(saved.getId());
        assertThat(found).isPresent();
        assertThat(found.get().getConnectAccountId()).isEqualTo(accountId);
        assertThat(found.get().getOrganizationId()).isEqualTo(1101L);
        assertThat(found.get().getOutstandingAmount()).isEqualTo(369L);
        assertThat(found.get().getCurrency()).isEqualTo("jpy");
        assertThat(found.get().getCreatedAt()).isNotNull();
        assertThat(found.get().getUpdatedAt()).isNotNull();
        assertThat(found.get().getDeletedAt()).isNull();
    }

    @Test
    @DisplayName("UNIQUE(connect_account_id, currency) 違反で重複 INSERT は拒否される")
    void uniqueConstraint_重複は拒否される() {
        UUID accountId = UUID.randomUUID();

        // 本クラスは @Transactional ではないため saveAndFlush をトランザクション外で呼ぶと
        // flush 自体が TransactionRequiredException で失敗してしまう。UNIQUE 違反を DB まで
        // 到達させるには flush が必要で、その flush にはトランザクション境界が要る。
        // そこで TransactionTemplate でトランザクション内 flush を行い、UNIQUE 制約が DB レベルで
        // 重複 INSERT を本当に拒否することを検証する（RepairPlanAuditLogIntegrationTest 等で確立済みの作法）。
        TransactionTemplate tx = new TransactionTemplate(transactionManager);

        // 1 件目は独立トランザクションでコミットし、DB に実在させる。
        tx.executeWithoutResult(status ->
                repository.saveAndFlush(newBalance(accountId, 1101L, 100L, "jpy")));

        // 2 件目はトランザクション内 flush 時に UNIQUE(connect_account_id, currency) 違反となる。
        assertThatThrownBy(() ->
                tx.executeWithoutResult(status ->
                        repository.saveAndFlush(newBalance(accountId, 1101L, 200L, "jpy"))))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    @DisplayName("同一 connect_account でも通貨が異なれば併存できる")
    void 通貨が異なれば併存できる() {
        UUID accountId = UUID.randomUUID();
        repository.saveAndFlush(newBalance(accountId, 1101L, 100L, "jpy"));
        repository.saveAndFlush(newBalance(accountId, 1101L, 200L, "usd"));

        assertThat(repository.findByConnectAccountIdAndCurrencyAndDeletedAtIsNull(accountId, "jpy"))
                .isPresent();
        assertThat(repository.findByConnectAccountIdAndCurrencyAndDeletedAtIsNull(accountId, "usd"))
                .isPresent();
    }

    @Test
    @DisplayName("findByConnectAccountIdAndCurrencyAndDeletedAtIsNull は論理削除行を除外する")
    void 論理削除行は除外される() {
        UUID accountId = UUID.randomUUID();
        FeeRecoveryBalanceEntity saved = repository.saveAndFlush(
                newBalance(accountId, 1101L, 100L, "jpy"));

        // アクティブな間は取得できる
        assertThat(repository.findByConnectAccountIdAndCurrencyAndDeletedAtIsNull(accountId, "jpy"))
                .isPresent();

        // 論理削除すると取得対象外になる
        saved.setDeletedAt(java.time.LocalDateTime.now());
        repository.saveAndFlush(saved);

        assertThat(repository.findByConnectAccountIdAndCurrencyAndDeletedAtIsNull(accountId, "jpy"))
                .as("論理削除済みの残高行は取得されないこと")
                .isEmpty();
    }

    @Test
    @DisplayName("TenantAware: organization_id で絞り込みアクティブ残高のみ取得・カウントできる")
    void tenantAware_organizationIdで絞り込める() {
        long orgA = 2201L;
        long orgB = 2202L;
        repository.saveAndFlush(newBalance(UUID.randomUUID(), orgA, 100L, "jpy"));
        repository.saveAndFlush(newBalance(UUID.randomUUID(), orgA, 200L, "jpy"));
        repository.saveAndFlush(newBalance(UUID.randomUUID(), orgB, 300L, "jpy"));

        assertThat(repository.findByOrganizationIdAndDeletedAtIsNull(orgA))
                .as("orgA のアクティブ残高は 2 件").hasSize(2);
        assertThat(repository.countByOrganizationIdAndDeletedAtIsNull(orgA)).isEqualTo(2L);

        Page<FeeRecoveryBalanceEntity> page =
                repository.findByOrganizationIdAndDeletedAtIsNull(orgA, PageRequest.of(0, 1));
        assertThat(page.getTotalElements()).isEqualTo(2L);
        assertThat(page.getContent()).hasSize(1);

        assertThat(repository.countByOrganizationIdAndDeletedAtIsNull(orgB)).isEqualTo(1L);
    }

    @Test
    @DisplayName("outstanding_amount は将来の符号反転に備え負値も保持できる（署名付き BIGINT）")
    void outstandingAmount_負値も保持できる() {
        UUID accountId = UUID.randomUUID();
        FeeRecoveryBalanceEntity saved = repository.saveAndFlush(
                newBalance(accountId, 1101L, -50L, "jpy"));

        assertThat(repository.findById(saved.getId()))
                .get()
                .extracting(FeeRecoveryBalanceEntity::getOutstandingAmount)
                .isEqualTo(-50L);
    }
}
