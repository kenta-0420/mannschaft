package com.mannschaft.app.membership.service;

import com.mannschaft.app.common.MembershipScopeQueryService;
import com.mannschaft.app.auth.entity.UserEntity;
import com.mannschaft.app.membership.domain.LeaveReason;
import com.mannschaft.app.membership.domain.RoleKind;
import com.mannschaft.app.membership.domain.ScopeType;
import com.mannschaft.app.membership.entity.MembershipEntity;
import com.mannschaft.app.organization.entity.OrganizationEntity;
import com.mannschaft.app.role.entity.RoleEntity;
import com.mannschaft.app.role.entity.UserRoleEntity;
import com.mannschaft.app.support.test.AbstractMySqlIntegrationTest;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/** CMP-1014: 所属列挙3用途の母集団差を実MySQLで固定する契約テスト。 */
@Transactional
@DisplayName("CMP-1014 所属スコープ列挙のMySQL契約")
@EnabledIf("com.mannschaft.app.support.test.AbstractMySqlIntegrationTest#isDockerAvailable")
class MembershipScopeQueryServiceContractIT extends AbstractMySqlIntegrationTest {

    private static final AtomicInteger SEQ = new AtomicInteger();
    private static final AtomicInteger TEAM_SEQ = new AtomicInteger();

    @PersistenceContext
    private EntityManager entityManager;

    @Autowired
    private MembershipScopeQueryService service;

    @Test
    @DisplayName("通常列挙はACTIVEのrole-only・MEMBER・SUPPORTERを統合し重複と退会済みを除く")
    void activeEnumerationUsesRoleUnionCurrentMembership() {
        Long userId = persistUser(UserEntity.UserStatus.ACTIVE);
        Long teamRoleOnly = nextTeamId();
        Long teamMemberOnly = nextTeamId();
        Long teamSupporterOnly = nextTeamId();
        Long teamDual = nextTeamId();
        Long teamLeft = nextTeamId();
        Long organizationRoleOnly = persistOrganization();
        Long organizationMemberOnly = persistOrganization();
        Long organizationSupporterOnly = persistOrganization();
        Long organizationDual = persistOrganization();
        Long organizationLeft = persistOrganization();

        grantTeamRole(userId, teamRoleOnly);
        addMembership(userId, ScopeType.TEAM, teamMemberOnly, RoleKind.MEMBER, null);
        addMembership(userId, ScopeType.TEAM, teamSupporterOnly, RoleKind.SUPPORTER, null);
        grantTeamRole(userId, teamDual);
        addMembership(userId, ScopeType.TEAM, teamDual, RoleKind.MEMBER, null);
        addMembership(userId, ScopeType.TEAM, teamLeft, RoleKind.MEMBER, LocalDateTime.now());

        grantOrganizationRole(userId, organizationRoleOnly);
        addMembership(userId, ScopeType.ORGANIZATION, organizationMemberOnly, RoleKind.MEMBER, null);
        addMembership(userId, ScopeType.ORGANIZATION, organizationSupporterOnly, RoleKind.SUPPORTER, null);
        grantOrganizationRole(userId, organizationDual);
        addMembership(userId, ScopeType.ORGANIZATION, organizationDual, RoleKind.MEMBER, null);
        addMembership(userId, ScopeType.ORGANIZATION, organizationLeft, RoleKind.MEMBER, LocalDateTime.now());
        flushClear();

        assertThat(service.findActiveTeamIds(userId))
                .containsExactlyInAnyOrder(teamRoleOnly, teamMemberOnly, teamSupporterOnly, teamDual);
        assertThat(service.findActiveOrganizationIds(userId))
                .containsExactlyInAnyOrder(
                        organizationRoleOnly,
                        organizationMemberOnly,
                        organizationSupporterOnly,
                        organizationDual);
    }

    @Test
    @DisplayName("非ACTIVEの子は通常列挙から外れGuardian subject列挙にはcurrent membershipだけ残る")
    void guardianSubjectPreservesCurrentMembershipForNonActiveChild() {
        Long frozenChild = persistUser(UserEntity.UserStatus.FROZEN);
        Long pendingChild = persistUser(UserEntity.UserStatus.PENDING_PARENTAL_CONSENT);
        Long frozenTeam = nextTeamId();
        Long frozenOrganization = persistOrganization();
        Long pendingTeam = nextTeamId();
        Long pendingOrganization = persistOrganization();
        Long leftTeam = nextTeamId();

        grantTeamRole(frozenChild, frozenTeam);
        grantOrganizationRole(frozenChild, frozenOrganization);
        addMembership(frozenChild, ScopeType.TEAM, frozenTeam, RoleKind.MEMBER, null);
        addMembership(frozenChild, ScopeType.ORGANIZATION, frozenOrganization, RoleKind.SUPPORTER, null);
        addMembership(frozenChild, ScopeType.TEAM, leftTeam, RoleKind.MEMBER, LocalDateTime.now());
        addMembership(pendingChild, ScopeType.TEAM, pendingTeam, RoleKind.MEMBER, null);
        addMembership(pendingChild, ScopeType.ORGANIZATION, pendingOrganization, RoleKind.MEMBER, null);
        flushClear();

        assertThat(service.findActiveTeamIds(frozenChild)).isEmpty();
        assertThat(service.findActiveOrganizationIds(frozenChild)).isEmpty();
        assertThat(service.findCurrentTeamIdsForAuthorizedGuardianSubject(frozenChild))
                .containsExactly(frozenTeam);
        assertThat(service.findCurrentOrganizationIdsForAuthorizedGuardianSubject(frozenChild))
                .containsExactly(frozenOrganization);
        assertThat(service.findCurrentTeamIdsForAuthorizedGuardianSubject(pendingChild))
                .containsExactly(pendingTeam);
        assertThat(service.findCurrentOrganizationIdsForAuthorizedGuardianSubject(pendingChild))
                .containsExactly(pendingOrganization);
    }

    @Test
    @DisplayName("membership直結列挙はrole-onlyを広げず別ユーザーと別scopeを混入させない")
    void currentMembershipEnumerationKeepsItsOriginalPopulation() {
        Long userId = persistUser(UserEntity.UserStatus.ACTIVE);
        Long otherUserId = persistUser(UserEntity.UserStatus.ACTIVE);
        Long roleOnlyTeam = nextTeamId();
        Long membershipTeam = nextTeamId();
        Long otherTeam = nextTeamId();
        Long membershipOrganization = persistOrganization();

        grantTeamRole(userId, roleOnlyTeam);
        addMembership(userId, ScopeType.TEAM, membershipTeam, RoleKind.MEMBER, null);
        addMembership(userId, ScopeType.ORGANIZATION, membershipOrganization, RoleKind.MEMBER, null);
        addMembership(otherUserId, ScopeType.TEAM, otherTeam, RoleKind.MEMBER, null);
        flushClear();

        assertThat(service.findCurrentMembershipTeamIds(userId)).containsExactly(membershipTeam);
        assertThat(service.findCurrentMembershipOrganizationIds(userId))
                .containsExactly(membershipOrganization);
    }

    private Long persistUser(UserEntity.UserStatus status) {
        int sequence = SEQ.incrementAndGet();
        UserEntity user = UserEntity.builder()
                .email("cmp1014-" + sequence + "@example.com")
                .lastName("所属")
                .firstName("列挙" + sequence)
                .displayName("所属列挙" + sequence)
                .status(status)
                .locale("ja")
                .timezone("Asia/Tokyo")
                .isSearchable(true)
                .build();
        entityManager.persist(user);
        return user.getId();
    }

    private Long persistOrganization() {
        int sequence = SEQ.incrementAndGet();
        OrganizationEntity organization = OrganizationEntity.builder()
                .slug("cmp1014-org-" + sequence)
                .name("CMP1014組織" + sequence)
                .orgType(OrganizationEntity.OrgType.ASSOCIATION)
                .visibility(OrganizationEntity.Visibility.PRIVATE)
                .hierarchyVisibility(OrganizationEntity.HierarchyVisibility.NONE)
                .supporterEnabled(true)
                .build();
        entityManager.persist(organization);
        return organization.getId();
    }

    private Long nextTeamId() {
        return 101_400L + TEAM_SEQ.incrementAndGet();
    }

    private Long roleId() {
        List<?> existing = entityManager.createQuery(
                        "SELECT r.id FROM RoleEntity r WHERE r.name = :name")
                .setParameter("name", "ADMIN")
                .setMaxResults(1)
                .getResultList();
        if (!existing.isEmpty()) {
            return (Long) existing.getFirst();
        }
        RoleEntity role = RoleEntity.builder()
                .name("ADMIN")
                .displayName("ADMIN")
                .priority(2)
                .isSystem(true)
                .build();
        entityManager.persist(role);
        entityManager.flush();
        return role.getId();
    }

    private void grantTeamRole(Long userId, Long teamId) {
        entityManager.persist(UserRoleEntity.builder()
                .userId(userId)
                .roleId(roleId())
                .teamId(teamId)
                .build());
    }

    private void grantOrganizationRole(Long userId, Long organizationId) {
        entityManager.persist(UserRoleEntity.builder()
                .userId(userId)
                .roleId(roleId())
                .organizationId(organizationId)
                .build());
    }

    private void addMembership(
            Long userId,
            ScopeType scopeType,
            Long scopeId,
            RoleKind roleKind,
            LocalDateTime leftAt) {
        entityManager.persist(MembershipEntity.builder()
                .userId(userId)
                .scopeType(scopeType)
                .scopeId(scopeId)
                .roleKind(roleKind)
                .joinedAt(LocalDateTime.now().minusDays(1))
                .leftAt(leftAt)
                .leaveReason(leftAt == null ? null : LeaveReason.SELF)
                .build());
    }

    private void flushClear() {
        entityManager.flush();
        entityManager.clear();
    }
}
