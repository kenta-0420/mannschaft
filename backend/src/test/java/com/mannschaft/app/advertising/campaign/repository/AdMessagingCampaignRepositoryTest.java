package com.mannschaft.app.advertising.campaign.repository;

import com.mannschaft.app.advertising.campaign.entity.AdMessagingCampaign;
import com.mannschaft.app.advertising.campaign.enums.AdCampaignStatus;
import com.mannschaft.app.advertising.campaign.enums.AdModerationStatus;
import com.mannschaft.app.membership.domain.ScopeType;
import com.mannschaft.app.support.test.AbstractMySqlIntegrationTest;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * F09.17 Phase 11-d-1 {@link AdMessagingCampaignRepository} 結合テスト。
 *
 * <p>scope_type / scope_id 2 カラム方式の新規メソッド群を検証する。
 * 旧 {@code findByOrganizationIdAndDeletedAtIsNull} 系は互換性のため Phase 11-d-2 まで残置。</p>
 */
@Transactional
@DisplayName("AdMessagingCampaignRepository scope ベースメソッド結合テスト")
@EnabledIf("com.mannschaft.app.support.test.AbstractMySqlIntegrationTest#isDockerAvailable")
class AdMessagingCampaignRepositoryTest extends AbstractMySqlIntegrationTest {

    @Autowired
    private AdMessagingCampaignRepository repository;

    @PersistenceContext
    private EntityManager em;

    private static final Long ORG_A = 9101L;
    private static final Long ORG_B = 9102L;
    private static final Long TEAM_A = 8001L;
    private static final Long ADVERTISER = 7001L;
    private static final Long CREATED_BY = 6001L;

    private AdMessagingCampaign persistCampaign(
            ScopeType scopeType, Long scopeId, Long organizationId, String name) {
        LocalDateTime now = LocalDateTime.now();
        AdMessagingCampaign entity = AdMessagingCampaign.builder()
                .advertiserAccountId(ADVERTISER)
                .organizationId(organizationId)
                .scopeType(scopeType)
                .scopeId(scopeId)
                .name(name)
                .status(AdCampaignStatus.DRAFT)
                .moderationStatus(AdModerationStatus.PENDING)
                .totalBudgetYen(10_000L)
                .consumedBudgetYen(0L)
                .startsAt(now.plusDays(1))
                .endsAt(now.plusDays(7))
                .scheduledTimezone("Asia/Tokyo")
                .createdByUserId(CREATED_BY)
                .build();
        em.persist(entity);
        em.flush();
        em.clear();
        return entity;
    }

    @Test
    @DisplayName("findByScopeTypeAndScopeIdAndDeletedAtIsNull_ORGANIZATION_スコープで取得できる")
    void findByScopeTypeAndScopeIdAndDeletedAtIsNull_ORGANIZATION_スコープで取得できる() {
        persistCampaign(ScopeType.ORGANIZATION, ORG_A, ORG_A, "org-a-campaign");
        persistCampaign(ScopeType.ORGANIZATION, ORG_B, ORG_B, "org-b-campaign");

        Page<AdMessagingCampaign> page = repository.findByScopeTypeAndScopeIdAndDeletedAtIsNull(
                ScopeType.ORGANIZATION, ORG_A, PageRequest.of(0, 10));

        assertThat(page.getTotalElements()).isEqualTo(1);
        assertThat(page.getContent().get(0).getName()).isEqualTo("org-a-campaign");
        assertThat(page.getContent().get(0).getScopeType()).isEqualTo(ScopeType.ORGANIZATION);
        assertThat(page.getContent().get(0).getScopeId()).isEqualTo(ORG_A);
    }

    @Test
    @DisplayName("findByScopeTypeAndScopeIdAndDeletedAtIsNull_TEAM_スコープで取得できる")
    void findByScopeTypeAndScopeIdAndDeletedAtIsNull_TEAM_スコープで取得できる() {
        // チームスコープのキャンペーンは organization_id NULL 可
        persistCampaign(ScopeType.TEAM, TEAM_A, null, "team-a-campaign");
        persistCampaign(ScopeType.ORGANIZATION, ORG_A, ORG_A, "org-a-campaign");

        Page<AdMessagingCampaign> page = repository.findByScopeTypeAndScopeIdAndDeletedAtIsNull(
                ScopeType.TEAM, TEAM_A, PageRequest.of(0, 10));

        assertThat(page.getTotalElements()).isEqualTo(1);
        assertThat(page.getContent().get(0).getName()).isEqualTo("team-a-campaign");
        assertThat(page.getContent().get(0).getScopeType()).isEqualTo(ScopeType.TEAM);
        assertThat(page.getContent().get(0).getScopeId()).isEqualTo(TEAM_A);
        assertThat(page.getContent().get(0).getOrganizationId()).isNull();
    }

    @Test
    @DisplayName("findByIdAndScopeTypeAndScopeIdAndDeletedAtIsNull_スコープ違いでは取得できない")
    void findByIdAndScopeTypeAndScopeIdAndDeletedAtIsNull_スコープ違いでは取得できない() {
        AdMessagingCampaign saved = persistCampaign(
                ScopeType.ORGANIZATION, ORG_A, ORG_A, "org-a-only");
        UUID id = saved.getId();

        Optional<AdMessagingCampaign> hit = repository
                .findByIdAndScopeTypeAndScopeIdAndDeletedAtIsNull(id, ScopeType.ORGANIZATION, ORG_A);
        Optional<AdMessagingCampaign> miss = repository
                .findByIdAndScopeTypeAndScopeIdAndDeletedAtIsNull(id, ScopeType.ORGANIZATION, ORG_B);
        Optional<AdMessagingCampaign> missScope = repository
                .findByIdAndScopeTypeAndScopeIdAndDeletedAtIsNull(id, ScopeType.TEAM, ORG_A);

        assertThat(hit).isPresent();
        assertThat(miss).isEmpty();
        assertThat(missScope).isEmpty();
    }

    @Test
    @DisplayName("countByScopeTypeAndScopeIdAndDeletedAtIsNull_件数を返せる")
    void countByScopeTypeAndScopeIdAndDeletedAtIsNull_件数を返せる() {
        persistCampaign(ScopeType.ORGANIZATION, ORG_A, ORG_A, "c1");
        persistCampaign(ScopeType.ORGANIZATION, ORG_A, ORG_A, "c2");
        persistCampaign(ScopeType.ORGANIZATION, ORG_B, ORG_B, "c3");

        long countA = repository.countByScopeTypeAndScopeIdAndDeletedAtIsNull(ScopeType.ORGANIZATION, ORG_A);
        long countB = repository.countByScopeTypeAndScopeIdAndDeletedAtIsNull(ScopeType.ORGANIZATION, ORG_B);

        assertThat(countA).isEqualTo(2L);
        assertThat(countB).isEqualTo(1L);
    }

    @Test
    @DisplayName("既存_ORGANIZATIONスコープ_は_互換メソッドからも同件取得できる")
    void 既存_ORGANIZATIONスコープ_は_互換メソッドからも同件取得できる() {
        persistCampaign(ScopeType.ORGANIZATION, ORG_A, ORG_A, "compat-a");

        Page<AdMessagingCampaign> scopeBased = repository.findByScopeTypeAndScopeIdAndDeletedAtIsNull(
                ScopeType.ORGANIZATION, ORG_A, PageRequest.of(0, 10));
        @SuppressWarnings("deprecation")
        Page<AdMessagingCampaign> orgBased = repository.findByOrganizationIdAndDeletedAtIsNull(
                ORG_A, PageRequest.of(0, 10));

        assertThat(scopeBased.getTotalElements()).isEqualTo(1);
        assertThat(orgBased.getTotalElements()).isEqualTo(1);
        assertThat(scopeBased.getContent().get(0).getId())
                .isEqualTo(orgBased.getContent().get(0).getId());
    }
}
