package com.mannschaft.app.advertising.repository;

import com.mannschaft.app.advertising.AdvertiserAccountStatus;
import com.mannschaft.app.advertising.BillingMethod;
import com.mannschaft.app.advertising.entity.AdvertiserAccountEntity;
import com.mannschaft.app.membership.domain.ScopeType;
import com.mannschaft.app.support.test.AbstractMySqlIntegrationTest;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * F09.17 Phase 11-d-1 {@link AdvertiserAccountRepository} 結合テスト。
 *
 * <p>scope_type / scope_id 2 カラム方式の新規メソッド群を検証する。
 * 旧 {@code findByOrganizationId} / {@code existsByOrganizationId} は互換性のため
 * Phase 11-d-2 まで残置。</p>
 */
@Transactional
@DisplayName("AdvertiserAccountRepository scope ベースメソッド結合テスト")
@EnabledIf("com.mannschaft.app.support.test.AbstractMySqlIntegrationTest#isDockerAvailable")
class AdvertiserAccountRepositoryTest extends AbstractMySqlIntegrationTest {

    @Autowired
    private AdvertiserAccountRepository repository;

    @PersistenceContext
    private EntityManager em;

    private static final Long ORG_A = 9201L;
    private static final Long ORG_B = 9202L;
    private static final Long TEAM_A = 8501L;

    private AdvertiserAccountEntity persistAccount(
            ScopeType scopeType, Long scopeId, Long organizationId, String company) {
        AdvertiserAccountEntity entity = AdvertiserAccountEntity.builder()
                .organizationId(organizationId)
                .scopeType(scopeType)
                .scopeId(scopeId)
                .status(AdvertiserAccountStatus.PENDING)
                .companyName(company)
                .contactEmail(company + "@example.com")
                .billingMethod(BillingMethod.STRIPE)
                .creditLimit(new BigDecimal("100000"))
                .build();
        em.persist(entity);
        em.flush();
        em.clear();
        return entity;
    }

    @Test
    @DisplayName("findByScopeTypeAndScopeIdAndDeletedAtIsNull_ORGANIZATION_スコープで取得できる")
    void findByScopeTypeAndScopeIdAndDeletedAtIsNull_ORGANIZATION_スコープで取得できる() {
        persistAccount(ScopeType.ORGANIZATION, ORG_A, ORG_A, "Acme");
        persistAccount(ScopeType.ORGANIZATION, ORG_B, ORG_B, "Beta");

        Optional<AdvertiserAccountEntity> hit = repository
                .findByScopeTypeAndScopeIdAndDeletedAtIsNull(ScopeType.ORGANIZATION, ORG_A);

        assertThat(hit).isPresent();
        assertThat(hit.get().getCompanyName()).isEqualTo("Acme");
        assertThat(hit.get().getScopeType()).isEqualTo(ScopeType.ORGANIZATION);
        assertThat(hit.get().getScopeId()).isEqualTo(ORG_A);
    }

    @Test
    @DisplayName("findByScopeTypeAndScopeIdAndDeletedAtIsNull_TEAM_スコープで取得できる")
    void findByScopeTypeAndScopeIdAndDeletedAtIsNull_TEAM_スコープで取得できる() {
        // チームスコープは organization_id NULL 可
        persistAccount(ScopeType.TEAM, TEAM_A, null, "TeamAdv");
        persistAccount(ScopeType.ORGANIZATION, ORG_A, ORG_A, "OrgAdv");

        Optional<AdvertiserAccountEntity> hit = repository
                .findByScopeTypeAndScopeIdAndDeletedAtIsNull(ScopeType.TEAM, TEAM_A);

        assertThat(hit).isPresent();
        assertThat(hit.get().getCompanyName()).isEqualTo("TeamAdv");
        assertThat(hit.get().getScopeType()).isEqualTo(ScopeType.TEAM);
        assertThat(hit.get().getScopeId()).isEqualTo(TEAM_A);
        assertThat(hit.get().getOrganizationId()).isNull();
    }

    @Test
    @DisplayName("existsByScopeTypeAndScopeIdAndDeletedAtIsNull_存在判定")
    void existsByScopeTypeAndScopeIdAndDeletedAtIsNull_存在判定() {
        persistAccount(ScopeType.ORGANIZATION, ORG_A, ORG_A, "Acme");

        assertThat(repository.existsByScopeTypeAndScopeIdAndDeletedAtIsNull(ScopeType.ORGANIZATION, ORG_A))
                .isTrue();
        assertThat(repository.existsByScopeTypeAndScopeIdAndDeletedAtIsNull(ScopeType.ORGANIZATION, ORG_B))
                .isFalse();
        assertThat(repository.existsByScopeTypeAndScopeIdAndDeletedAtIsNull(ScopeType.TEAM, ORG_A))
                .isFalse();
    }

    @Test
    @DisplayName("旧_findByOrganizationId_と_新_findByScopeTypeAndScopeId_は_ORGANIZATIONスコープで同一結果")
    void 旧_findByOrganizationId_と_新_findByScopeTypeAndScopeId_は_ORGANIZATIONスコープで同一結果() {
        persistAccount(ScopeType.ORGANIZATION, ORG_A, ORG_A, "Compat");

        @SuppressWarnings("deprecation")
        Optional<AdvertiserAccountEntity> orgBased = repository.findByOrganizationId(ORG_A);
        Optional<AdvertiserAccountEntity> scopeBased = repository
                .findByScopeTypeAndScopeIdAndDeletedAtIsNull(ScopeType.ORGANIZATION, ORG_A);

        assertThat(orgBased).isPresent();
        assertThat(scopeBased).isPresent();
        assertThat(orgBased.get().getId()).isEqualTo(scopeBased.get().getId());
    }
}
