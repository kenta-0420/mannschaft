package com.mannschaft.app.common.visibility;

import com.mannschaft.app.auth.entity.UserEntity;
import com.mannschaft.app.membership.domain.RoleKind;
import com.mannschaft.app.membership.domain.ScopeType;
import com.mannschaft.app.organization.entity.OrganizationEntity;
import com.mannschaft.app.support.test.AbstractMySqlIntegrationTest;
import com.mannschaft.app.support.test.MembershipTestHelper;
import com.mannschaft.app.team.entity.TeamOrgMembershipEntity;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.hibernate.SessionFactory;
import org.hibernate.stat.Statistics;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.Collections;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * CMP-027（試練・先行 red）— {@link MembershipBatchQueryService#snapshotForUser} の
 * 下向き再帰スナップショット（{@code descendantMemberOfOrgIds} / {@code descendantRoleByOrgId}）が、
 * <b>memberships のみ</b>の素メンバー（V60.010 後に唯一成立しうる形）を取りこぼさないことを固定する。
 *
 * <p>フィクスチャは memberships のみ（{@code user_roles} に MEMBER/SUPPORTER 行を張らない）。
 * 実装が {@code user_roles} 一色の間はスナップショットの下向き再帰が空になり、A1/A2 は red。
 * SQL 本数（A8）は memberships 系統を織り込んでも既存予算（最大 7）を超えないことを番人として固定する。</p>
 */
@Transactional
@DisplayName("MembershipBatchQueryService 下向き再帰 memberships-only スナップショット（CMP-027 試練）")
@EnabledIf("com.mannschaft.app.support.test.AbstractMySqlIntegrationTest#isDockerAvailable")
class MembershipBatchQueryServiceDescendantMembershipOnlyIntegrationTest extends AbstractMySqlIntegrationTest {

    private static final AtomicInteger SEQ = new AtomicInteger(0);

    @Autowired
    private MembershipBatchQueryService service;

    @PersistenceContext
    private EntityManager em;

    private int nextSeq() {
        return SEQ.incrementAndGet();
    }

    private Long persistOrganization(Long parentOrganizationId) {
        int n = nextSeq();
        OrganizationEntity org = OrganizationEntity.builder()
                .slug("c27s-org-" + n)
                .name("CMP027srv組織" + n)
                .orgType(OrganizationEntity.OrgType.ASSOCIATION)
                .parentOrganizationId(parentOrganizationId)
                .visibility(OrganizationEntity.Visibility.PRIVATE)
                .hierarchyVisibility(OrganizationEntity.HierarchyVisibility.NONE)
                .supporterEnabled(true)
                .build();
        em.persist(org);
        return org.getId();
    }

    private Long persistActiveUser() {
        int n = nextSeq();
        UserEntity user = UserEntity.builder()
                .email("c27s-user-" + n + "@example.com")
                .lastName("配下")
                .firstName("素" + n)
                .displayName("配下素" + n)
                .status(UserEntity.UserStatus.ACTIVE)
                .locale("ja")
                .timezone("Asia/Tokyo")
                .isSearchable(true)
                .build();
        em.persist(user);
        return user.getId();
    }

    private void linkTeamToOrg(Long teamId, Long organizationId) {
        TeamOrgMembershipEntity tom = TeamOrgMembershipEntity.builder()
                .teamId(teamId)
                .organizationId(organizationId)
                .status(TeamOrgMembershipEntity.Status.ACTIVE)
                .invitedAt(LocalDateTime.now())
                .build();
        em.persist(tom);
    }

    private void addActiveMembership(Long userId, ScopeType scopeType, Long scopeId, RoleKind roleKind) {
        MembershipTestHelper.insertMembership(em, userId, scopeType, scopeId, roleKind);
    }

    private void flushClear() {
        em.flush();
        em.clear();
    }

    private Statistics statisticsCleared() {
        SessionFactory sf = em.getEntityManagerFactory().unwrap(SessionFactory.class);
        Statistics stats = sf.getStatistics();
        stats.setStatisticsEnabled(true);
        stats.clear();
        return stats;
    }

    // =====================================================================
    // A1: memberships のみの配下チーム MEMBER が snapshot の下向き再帰に載る（red）
    // =====================================================================

    @Test
    @DisplayName("ac_a1_snapshotの下向き再帰はmembershipのみの配下チームMEMBERを含み_roleByOrgIdにMEMBERを持つ")
    void ac_a1_snapshotが配下membershipのみメンバーを含む() {
        Long rootOrg = persistOrganization(null);
        Long leafOrg = persistOrganization(rootOrg);
        Long leafTeam = 710_001L;
        linkTeamToOrg(leafTeam, leafOrg);

        Long plainMember = persistActiveUser();
        addActiveMembership(plainMember, ScopeType.TEAM, leafTeam, RoleKind.MEMBER);
        flushClear();

        ScopeKey rootScope = new ScopeKey("ORGANIZATION", rootOrg);
        UserScopeRoleSnapshot snapshot = service.snapshotForUser(
                plainMember,
                Collections.emptySet(),
                Collections.emptySet(),
                Set.of(rootScope));

        assertThat(snapshot.isDescendantMemberOf(rootScope))
                .as("memberships のみの配下チーム MEMBER が snapshot の下向き再帰に載っていない（CMP-027）")
                .isTrue();
        assertThat(snapshot.descendantMemberOfOrgIds()).contains(rootOrg);
        assertThat(snapshot.descendantRoleByOrgId())
                .as("下向き再帰のロール名解決が memberships-only メンバーで欠落している")
                .containsEntry(rootOrg, "MEMBER");
    }

    // =====================================================================
    // A2: 複数経路 → priority 最小の MEMBER へ畳み込む（red）
    // =====================================================================

    @Test
    @DisplayName("ac_a2_配下でMEMBERとSUPPORTERを併せ持つ場合はMEMBER(priority最小)へ畳み込む")
    void ac_a2_複数経路はMEMBERへ畳み込む() {
        Long rootOrg = persistOrganization(null);
        Long leafOrg = persistOrganization(rootOrg);
        Long teamMember = 710_010L;
        Long teamSupporter = 710_011L;
        linkTeamToOrg(teamMember, leafOrg);
        linkTeamToOrg(teamSupporter, leafOrg);

        Long mixed = persistActiveUser();
        addActiveMembership(mixed, ScopeType.TEAM, teamMember, RoleKind.MEMBER);
        addActiveMembership(mixed, ScopeType.TEAM, teamSupporter, RoleKind.SUPPORTER);
        flushClear();

        ScopeKey rootScope = new ScopeKey("ORGANIZATION", rootOrg);
        UserScopeRoleSnapshot snapshot = service.snapshotForUser(
                mixed, Collections.emptySet(), Collections.emptySet(), Set.of(rootScope));

        assertThat(snapshot.descendantRoleByOrgId())
                .as("複数経路は priority 最小（MEMBER）へ畳み込むべし")
                .containsEntry(rootOrg, "MEMBER");
    }

    // =====================================================================
    // A8: SQL 本数（番人）— 下向き再帰スコープありでも既存予算（最大7）内
    // =====================================================================

    @Test
    @DisplayName("ac_a8_下向き再帰スコープありでもSQL本数は既存予算(最大7)を超えない")
    void ac_a8_SQL本数が予算内() {
        Long rootOrg = persistOrganization(null);
        Long leafOrg = persistOrganization(rootOrg);
        Long leafTeam = 710_020L;
        linkTeamToOrg(leafTeam, leafOrg);

        Long plainMember = persistActiveUser();
        addActiveMembership(plainMember, ScopeType.TEAM, leafTeam, RoleKind.MEMBER);
        flushClear();

        Statistics stats = statisticsCleared();
        service.snapshotForUser(
                plainMember,
                Collections.emptySet(),
                Collections.emptySet(),
                Set.of(new ScopeKey("ORGANIZATION", rootOrg)));

        assertThat(stats.getPrepareStatementCount())
                .as("下向き再帰の memberships 対応で SQL 本数が増えていない（N+1 でない）")
                .isLessThanOrEqualTo(7L);
    }
}
