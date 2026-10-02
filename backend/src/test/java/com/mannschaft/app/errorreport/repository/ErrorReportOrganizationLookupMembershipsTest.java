package com.mannschaft.app.errorreport.repository;

import com.mannschaft.app.auth.entity.UserEntity;
import com.mannschaft.app.membership.domain.RoleKind;
import com.mannschaft.app.membership.domain.ScopeType;
import com.mannschaft.app.membership.entity.MembershipEntity;
import com.mannschaft.app.organization.entity.OrganizationEntity;
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
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Issue #2786 丙層 AC-24: {@code ErrorReportRepository#findOrganizationIdByUserId} が
 * {@code memberships} 専属の一般メンバーの所属組織を解決できない欠陥の
 * 受け入れテスト（試練 = テスト先行）。
 *
 * <p>本メソッドは {@code team_org_memberships} と {@code user_roles} の結合で
 * 組織 ID を引く。{@code V60.010} で MEMBER / SUPPORTER の在籍行が
 * {@code memberships} へ移行した結果、一般メンバーが送ったエラーレポートは
 * 組織紐付けが解決できず NULL 化し、組織管理者の一覧から消える。</p>
 *
 * <p>実 MySQL（Testcontainers）に 2 系統の在籍行を永続化して検証する。</p>
 */
@Transactional
@DisplayName("Issue #2786 丙層: エラーレポートの組織ルックアップが memberships 専属メンバーを解決できない")
@EnabledIf("com.mannschaft.app.support.test.AbstractMySqlIntegrationTest#isDockerAvailable")
class ErrorReportOrganizationLookupMembershipsTest extends AbstractMySqlIntegrationTest {

    /** テスト内でユニークな slug / email を払い出すためのカウンタ。 */
    private static final AtomicInteger SEQ = new AtomicInteger(0);

    /** 本テスト専用のチーム ID 採番（team_org_memberships のみ参照するため teams 行は不要）。 */
    private static final AtomicInteger TEAM_SEQ = new AtomicInteger(0);

    @PersistenceContext
    private EntityManager em;

    @Autowired
    private ErrorReportRepository errorReportRepository;

    // ---------------------------------------------------------------------
    // 永続化ヘルパー
    // ---------------------------------------------------------------------

    private int nextSeq() {
        return SEQ.incrementAndGet();
    }

    private Long nextTeamId() {
        return 787_000L + TEAM_SEQ.incrementAndGet();
    }

    private Long persistOrganization() {
        int n = nextSeq();
        OrganizationEntity org = OrganizationEntity.builder()
                .slug("i2786-err-org-" + n)
                .name("2786エラー組織" + n)
                .orgType(OrganizationEntity.OrgType.ASSOCIATION)
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
                .email("i2786-err-" + n + "@example.com")
                .lastName("報告")
                .firstName("者" + n)
                .displayName("報告者" + n)
                .status(UserEntity.UserStatus.ACTIVE)
                .locale("ja")
                .timezone("Asia/Tokyo")
                .isSearchable(true)
                .build();
        em.persist(user);
        return user.getId();
    }

    /**
     * 指定名のロールを取得（無ければ作成）する。
     * test profile は Flyway 無効で {@code roles} が空表のため、必要なロール行は自前で用意する。
     */
    private Long persistRoleIfNeeded(String name, int priority) {
        List<?> found = em.createNativeQuery("SELECT id FROM roles WHERE name = :name")
                .setParameter("name", name)
                .getResultList();
        if (!found.isEmpty()) {
            return ((Number) found.get(0)).longValue();
        }
        RoleEntity role = RoleEntity.builder()
                .name(name)
                .displayName(name)
                .priority(priority)
                .isSystem(true)
                .build();
        em.persist(role);
        em.flush();
        return role.getId();
    }

    private void grantTeamRole(Long userId, Long teamId, String roleName, int priority) {
        UserRoleEntity ur = UserRoleEntity.builder()
                .userId(userId)
                .roleId(persistRoleIfNeeded(roleName, priority))
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

    // =====================================================================
    // AC-24
    // =====================================================================

    /**
     * AC-24: 組織配下チームに {@code memberships} のみで在籍する一般メンバーの
     * 所属組織が解決されること（チーム経路）。
     */
    @Test
    @DisplayName("AC-24: 配下チームにmemberships専属で在籍する一般メンバーの所属組織が解決される")
    void ac24_配下チームのmemberships専属メンバーの所属組織が解決される() {
        Long orgId = persistOrganization();
        Long teamId = nextTeamId();
        linkTeamToOrg(teamId, orgId, TeamOrgMembershipEntity.Status.ACTIVE);

        Long membershipsOnly = persistActiveUser();
        addMembership(membershipsOnly, ScopeType.TEAM, teamId, RoleKind.MEMBER, null);
        flushClear();

        assertThat(errorReportRepository.findOrganizationIdByUserId(membershipsOnly))
                .as("一般メンバーのエラーレポートも組織へ紐付けられるべきである")
                .contains(orgId);
    }

    /**
     * AC-24: 組織に {@code memberships} で直接在籍する一般メンバーの
     * 所属組織が解決されること（組織直属経路）。
     */
    @Test
    @DisplayName("AC-24: 組織に直接memberships在籍する一般メンバーの所属組織が解決される")
    void ac24_組織直属のmemberships専属メンバーの所属組織が解決される() {
        Long orgId = persistOrganization();

        Long membershipsOnly = persistActiveUser();
        addMembership(membershipsOnly, ScopeType.ORGANIZATION, orgId, RoleKind.MEMBER, null);
        flushClear();

        assertThat(errorReportRepository.findOrganizationIdByUserId(membershipsOnly))
                .as("組織へ直接在籍する一般メンバーの所属組織も解決されるべきである")
                .contains(orgId);
    }

    /**
     * AC-24【陽性対照】: {@code user_roles} に ADMIN 行のみを持つ役職者の
     * 所属組織が従来どおり解決されること。
     */
    @Test
    @DisplayName("AC-24【陽性対照】: user_roles専属のADMIN役職者の所属組織は従来どおり解決される")
    void ac24_陽性対照_userRoles専属の役職者は従来どおり解決される() {
        Long orgId = persistOrganization();
        Long teamId = nextTeamId();
        linkTeamToOrg(teamId, orgId, TeamOrgMembershipEntity.Status.ACTIVE);

        Long admin = persistActiveUser();
        grantTeamRole(admin, teamId, "ADMIN", 2);
        flushClear();

        assertThat(errorReportRepository.findOrganizationIdByUserId(admin))
                .as("user_roles 由来の役職者の所属組織解決は変わらない")
                .contains(orgId);
    }

    /**
     * AC-24【境界】: 退会済 membership しか持たない者・
     * 非 ACTIVE な {@code team_org_memberships} しか経由しない者では解決されないこと。
     */
    @Test
    @DisplayName("AC-24【境界】: 退会済membership・非ACTIVEなチーム所属では組織が解決されない")
    void ac24_境界_退会済と非ACTIVEチーム所属では解決されない() {
        Long orgId = persistOrganization();
        Long activeTeam = nextTeamId();
        linkTeamToOrg(activeTeam, orgId, TeamOrgMembershipEntity.Status.ACTIVE);
        Long pendingTeam = nextTeamId();
        linkTeamToOrg(pendingTeam, orgId, TeamOrgMembershipEntity.Status.PENDING);

        Long leftMember = persistActiveUser();
        addMembership(leftMember, ScopeType.TEAM, activeTeam, RoleKind.MEMBER, LocalDateTime.now().minusDays(1));

        Long pendingTeamMember = persistActiveUser();
        addMembership(pendingTeamMember, ScopeType.TEAM, pendingTeam, RoleKind.MEMBER, null);
        flushClear();

        assertThat(errorReportRepository.findOrganizationIdByUserId(leftMember))
                .as("退会済 membership しか持たない者の所属組織を解決してはならない")
                .isEmpty();
        assertThat(errorReportRepository.findOrganizationIdByUserId(pendingTeamMember))
                .as("組織への参加が ACTIVE でないチームを経由して所属組織を解決してはならない")
                .isEmpty();
    }

    /**
     * AC-24【境界】: どこにも在籍しない者では解決されないこと。
     */
    @Test
    @DisplayName("AC-24【境界】: どこにも在籍しない利用者では組織が解決されない")
    void ac24_境界_無所属の利用者では解決されない() {
        Long orgId = persistOrganization();
        Long teamId = nextTeamId();
        linkTeamToOrg(teamId, orgId, TeamOrgMembershipEntity.Status.ACTIVE);
        addMembership(persistActiveUser(), ScopeType.TEAM, teamId, RoleKind.MEMBER, null);

        Long outsider = persistActiveUser();
        flushClear();

        assertThat(errorReportRepository.findOrganizationIdByUserId(outsider))
                .as("無所属の利用者に無関係な組織を紐付けてはならない")
                .isEmpty();
    }
}
