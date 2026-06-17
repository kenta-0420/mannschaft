package com.mannschaft.app.role.repository;

import com.mannschaft.app.auth.entity.UserEntity;
import com.mannschaft.app.membership.domain.RoleKind;
import com.mannschaft.app.membership.domain.ScopeType;
import com.mannschaft.app.membership.entity.MembershipEntity;
import com.mannschaft.app.role.entity.RoleEntity;
import com.mannschaft.app.role.entity.UserRoleEntity;
import com.mannschaft.app.support.test.AbstractMySqlIntegrationTest;
import com.mannschaft.app.team.entity.TeamOrgMembershipEntity;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link UserRoleRepository#findDistributionUserIdsForOrganization(Long, boolean)} の結合テスト。
 *
 * <p>(B) 組織→参加チーム配信 案C フェーズA 隊A のプリミティブ。
 * 「直属 ∪ 配下参加チーム(ACTIVE)」展開、DISTINCT、離脱チーム除外、退会/非アクティブ除外、
 * SUPPORTER 除外/包含（memberships.role_kind 駆動）を検証する。</p>
 */
@Transactional
@DisplayName("UserRoleRepository#findDistributionUserIdsForOrganization 結合テスト")
@EnabledIf("com.mannschaft.app.support.test.AbstractMySqlIntegrationTest#isDockerAvailable")
class UserRoleDistributionRepositoryTest extends AbstractMySqlIntegrationTest {

    private static final Long ORG_ID = 9001L;
    private static final Long OTHER_ORG_ID = 9999L;

    @PersistenceContext
    private EntityManager em;

    @Autowired
    private UserRoleRepository userRoleRepository;

    private Long roleId;

    private Long persistActiveUser(String email) {
        UserEntity user = UserEntity.builder()
                .email(email)
                .lastName("配信")
                .firstName("対象")
                .displayName("配信対象")
                .status(UserEntity.UserStatus.ACTIVE)
                .locale("ja")
                .timezone("Asia/Tokyo")
                .isSearchable(true)
                .build();
        em.persist(user);
        return user.getId();
    }

    private void persistRoleIfNeeded() {
        if (roleId != null) {
            return;
        }
        RoleEntity role = RoleEntity.builder()
                .name("MEMBER")
                .displayName("メンバー")
                .priority(50)
                .isSystem(true)
                .build();
        em.persist(role);
        roleId = role.getId();
    }

    private void grantOrgRole(Long userId, Long organizationId) {
        persistRoleIfNeeded();
        UserRoleEntity ur = UserRoleEntity.builder()
                .userId(userId)
                .roleId(roleId)
                .organizationId(organizationId)
                .build();
        em.persist(ur);
    }

    private void grantTeamRole(Long userId, Long teamId) {
        persistRoleIfNeeded();
        UserRoleEntity ur = UserRoleEntity.builder()
                .userId(userId)
                .roleId(roleId)
                .teamId(teamId)
                .build();
        em.persist(ur);
    }

    private void linkTeamToOrg(Long teamId, Long organizationId, TeamOrgMembershipEntity.Status status) {
        TeamOrgMembershipEntity tom = TeamOrgMembershipEntity.builder()
                .teamId(teamId)
                .organizationId(organizationId)
                .status(status)
                .invitedAt(LocalDateTime.now())
                .build();
        em.persist(tom);
    }

    private void addMembership(Long userId, ScopeType scopeType, Long scopeId,
                               RoleKind roleKind, LocalDateTime leftAt) {
        MembershipEntity ms = MembershipEntity.builder()
                .userId(userId)
                .scopeType(scopeType)
                .scopeId(scopeId)
                .roleKind(roleKind)
                .joinedAt(LocalDateTime.now())
                .leftAt(leftAt)
                .build();
        em.persist(ms);
    }

    private void flushClear() {
        em.flush();
        em.clear();
    }

    @Test
    @DisplayName("直属メンバーのみ_組織直属user_roleが返る")
    void 直属メンバーのみ() {
        Long u = persistActiveUser("direct@example.com");
        grantOrgRole(u, ORG_ID);
        flushClear();

        List<Long> result = userRoleRepository.findDistributionUserIdsForOrganization(ORG_ID, false);

        assertThat(result).containsExactly(u);
    }

    @Test
    @DisplayName("配下チームのみ_ACTIVEチームのメンバーが返る")
    void 配下チームのみ() {
        Long u = persistActiveUser("team@example.com");
        Long teamId = 5001L;
        linkTeamToOrg(teamId, ORG_ID, TeamOrgMembershipEntity.Status.ACTIVE);
        grantTeamRole(u, teamId);
        flushClear();

        List<Long> result = userRoleRepository.findDistributionUserIdsForOrganization(ORG_ID, false);

        assertThat(result).containsExactly(u);
    }

    @Test
    @DisplayName("直属と配下の重複_DISTINCTで1回だけ返る")
    void 直属と配下の重複はDISTINCT() {
        Long u = persistActiveUser("both@example.com");
        Long teamId = 5002L;
        linkTeamToOrg(teamId, ORG_ID, TeamOrgMembershipEntity.Status.ACTIVE);
        grantOrgRole(u, ORG_ID);
        grantTeamRole(u, teamId);
        flushClear();

        List<Long> result = userRoleRepository.findDistributionUserIdsForOrganization(ORG_ID, false);

        assertThat(result).containsExactly(u);
    }

    @Test
    @DisplayName("離脱チーム(status!=ACTIVE)のメンバーは除外される")
    void 離脱チームは除外() {
        Long u = persistActiveUser("pending@example.com");
        Long teamId = 5003L;
        linkTeamToOrg(teamId, ORG_ID, TeamOrgMembershipEntity.Status.PENDING);
        grantTeamRole(u, teamId);
        flushClear();

        List<Long> result = userRoleRepository.findDistributionUserIdsForOrganization(ORG_ID, false);

        assertThat(result).isEmpty();
    }

    @Test
    @DisplayName("退会/非アクティブユーザーは除外される")
    void 退会非アクティブは除外() {
        // FROZEN ユーザー
        UserEntity frozen = UserEntity.builder()
                .email("frozen@example.com")
                .lastName("凍結").firstName("太郎").displayName("凍結太郎")
                .status(UserEntity.UserStatus.FROZEN)
                .locale("ja").timezone("Asia/Tokyo").isSearchable(true)
                .build();
        em.persist(frozen);
        grantOrgRole(frozen.getId(), ORG_ID);

        // 論理削除ユーザー
        UserEntity deleted = UserEntity.builder()
                .email("deleted@example.com")
                .lastName("削除").firstName("花子").displayName("削除花子")
                .status(UserEntity.UserStatus.ACTIVE)
                .locale("ja").timezone("Asia/Tokyo").isSearchable(true)
                .deletedAt(LocalDateTime.now())
                .build();
        em.persist(deleted);
        grantOrgRole(deleted.getId(), ORG_ID);

        // アクティブユーザー
        Long active = persistActiveUser("active@example.com");
        grantOrgRole(active, ORG_ID);
        flushClear();

        List<Long> result = userRoleRepository.findDistributionUserIdsForOrganization(ORG_ID, false);

        assertThat(result).containsExactly(active);
    }

    @Test
    @DisplayName("includeSupporters=false_組織SUPPORTERは除外される")
    void includeSupportersFalseで組織SUPPORTER除外() {
        Long supporter = persistActiveUser("supporter@example.com");
        grantOrgRole(supporter, ORG_ID);
        addMembership(supporter, ScopeType.ORGANIZATION, ORG_ID, RoleKind.SUPPORTER, null);

        Long member = persistActiveUser("member@example.com");
        grantOrgRole(member, ORG_ID);
        addMembership(member, ScopeType.ORGANIZATION, ORG_ID, RoleKind.MEMBER, null);
        flushClear();

        List<Long> result = userRoleRepository.findDistributionUserIdsForOrganization(ORG_ID, false);

        assertThat(result).containsExactly(member);
    }

    @Test
    @DisplayName("includeSupporters=true_組織SUPPORTERも含まれる")
    void includeSupportersTrueで組織SUPPORTER包含() {
        Long supporter = persistActiveUser("supporter2@example.com");
        grantOrgRole(supporter, ORG_ID);
        addMembership(supporter, ScopeType.ORGANIZATION, ORG_ID, RoleKind.SUPPORTER, null);
        flushClear();

        List<Long> result = userRoleRepository.findDistributionUserIdsForOrganization(ORG_ID, true);

        assertThat(result).containsExactly(supporter);
    }

    @Test
    @DisplayName("離脱済みSUPPORTER所属(left_at!=NULL)は除外判定の対象外_配信に含まれる")
    void 離脱済みSUPPORTERは除外判定対象外() {
        Long u = persistActiveUser("leftsupporter@example.com");
        grantOrgRole(u, ORG_ID);
        // 過去に SUPPORTER だったが離脱済み（left_at セット）→ 在籍中の SUPPORTER ではないので除外しない
        addMembership(u, ScopeType.ORGANIZATION, ORG_ID, RoleKind.SUPPORTER, LocalDateTime.now());
        flushClear();

        List<Long> result = userRoleRepository.findDistributionUserIdsForOrganization(ORG_ID, false);

        assertThat(result).containsExactly(u);
    }

    @Test
    @DisplayName("配下チームでMEMBER且つ組織でSUPPORTER_MEMBER優先で除外されない")
    void MEMBER優先で除外されない() {
        Long u = persistActiveUser("mixed@example.com");
        Long teamId = 5004L;
        linkTeamToOrg(teamId, ORG_ID, TeamOrgMembershipEntity.Status.ACTIVE);
        grantTeamRole(u, teamId);
        // 組織では SUPPORTER だが、配下チームでは MEMBER → MEMBER 優先で配信対象
        addMembership(u, ScopeType.ORGANIZATION, ORG_ID, RoleKind.SUPPORTER, null);
        addMembership(u, ScopeType.TEAM, teamId, RoleKind.MEMBER, null);
        flushClear();

        List<Long> result = userRoleRepository.findDistributionUserIdsForOrganization(ORG_ID, false);

        assertThat(result).containsExactly(u);
    }

    @Test
    @DisplayName("別組織の直属メンバーは対象外")
    void 別組織は対象外() {
        Long u = persistActiveUser("otherorg@example.com");
        grantOrgRole(u, OTHER_ORG_ID);
        flushClear();

        List<Long> result = userRoleRepository.findDistributionUserIdsForOrganization(ORG_ID, false);

        assertThat(result).isEmpty();
    }
}
