package com.mannschaft.app.team;

import com.mannschaft.app.organization.service.OrganizationMembershipService;
import com.mannschaft.app.role.repository.UserRoleRepository;
import com.mannschaft.app.support.test.AbstractMySqlIntegrationTest;
import com.mannschaft.app.team.entity.TeamOrgMembershipEntity;
import com.mannschaft.app.team.repository.TeamOrgMembershipRepository;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * F01.2.1 §9.1・AC-N06（部隊 3-F）— 1チームが複数の親組織へ同時に ACTIVE で加盟していても、
 * 配下メンバー数・チーム一覧などの集計で<strong>二重に数えない</strong>ことの契約テスト。
 *
 * <p>フィクスチャ: 親組織 P の配下に組織 X・Y があり、チーム T が X と Y の両方に ACTIVE で加盟する。
 * T には2人のメンバー（memberships）がいる。P から見ると T は「X 経由」と「Y 経由」の2経路で届くが、
 * 数えるのはユーザー・チーム単位で1回でなければならない。</p>
 *
 * <p>team_org_memberships の書き込み API を経由せず native INSERT で行を作る。Repository・Service はモックしない。</p>
 */
@Transactional
@EnabledIf("com.mannschaft.app.support.test.AbstractMySqlIntegrationTest#isDockerAvailable")
@DisplayName("F01.2.1 3-F 複数親でも集計で二重に数えない（AC-N06）")
class TeamOrgMultiParentAggregateNoDoubleCountIT extends AbstractMySqlIntegrationTest {

    private static final int MAX_DEPTH = 10;

    @Autowired
    private UserRoleRepository userRoleRepository;

    @Autowired
    private TeamOrgMembershipRepository teamOrgMembershipRepository;

    @Autowired
    private OrganizationMembershipService organizationMembershipService;

    @PersistenceContext
    private EntityManager em;

    private Long orgP;
    private Long orgX;
    private Long orgY;
    private Long teamT;
    private Long userA;
    private Long userB;

    @BeforeEach
    void setUp() {
        String sfx = String.valueOf(System.nanoTime());
        orgP = insertOrganization("N06 親P " + sfx, "n06-p-" + sfx);
        orgX = insertOrganization("N06 組織X " + sfx, "n06-x-" + sfx);
        orgY = insertOrganization("N06 組織Y " + sfx, "n06-y-" + sfx);
        em.createNativeQuery("UPDATE organizations SET parent_organization_id = :p WHERE id IN (:x, :y)")
                .setParameter("p", orgP)
                .setParameter("x", orgX)
                .setParameter("y", orgY)
                .executeUpdate();
        teamT = insertTeam("N06 チームT " + sfx, "n06-t-" + sfx);
        userA = insertUser("n06-a-" + sfx + "@example.com");
        userB = insertUser("n06-b-" + sfx + "@example.com");
        insertTeamMember(userA, teamT);
        insertTeamMember(userB, teamT);
        linkTeamToOrganization(teamT, orgX, 2);
        linkTeamToOrganization(teamT, orgY, 1);
        em.flush();
        em.clear();
    }

    @Test
    @DisplayName("AC-N06 親組織配下の配信対象ユーザーは、2経路（X 経由・Y 経由）でも1人1回だけ")
    void 配信対象ユーザーは重複しない() {
        List<Long> userIds = organizationMembershipService.resolveOrgDistributionUserIds(orgP, false);

        assertThat(userIds).containsExactlyInAnyOrder(userA, userB);
    }

    @Test
    @DisplayName("AC-N06 親組織配下の母集団人数は2（経路数の倍の4にならない）")
    void 母集団人数は重複しない() {
        assertThat(userRoleRepository.countDistributionUserIdsForOrganizationRecursive(orgP, false, MAX_DEPTH))
                .isEqualTo(2L);
    }

    @Test
    @DisplayName("AC-N06 X・Y それぞれの配下でも、配信対象は A・B の2人で、配下人数は2（代表親だけ・片方だけ0にならない）")
    void 各親組織の配下でも配信対象と人数は2() {
        for (Long org : List.of(orgX, orgY)) {
            assertThat(organizationMembershipService.resolveOrgDistributionUserIds(org, false))
                    .as("組織 %s の配信対象", org)
                    .containsExactlyInAnyOrder(userA, userB);
            assertThat(userRoleRepository.countDistributionUserIdsForOrganizationRecursive(org, false, MAX_DEPTH))
                    .as("組織 %s の配下人数", org)
                    .isEqualTo(2L);
        }
    }

    @Test
    @DisplayName("AC-N06 チームのメンバー数は2")
    void チームのメンバー数は重複しない() {
        assertThat(userRoleRepository.countMembersByScope("TEAM", teamT)).isEqualTo(2);
        assertThat(userRoleRepository.countUserIdsByScope("TEAM", teamT)).isEqualTo(2L);
    }

    @Test
    @DisplayName("AC-N06 組織ごとの配下チーム一覧には T が1回ずつ載り、組織ごとの加盟数は1")
    void 組織ごとの配下チームは1回ずつ() {
        assertThat(userRoleRepository.findTeamIdsByOrganizationId(orgX)).containsExactly(teamT);
        assertThat(userRoleRepository.findTeamIdsByOrganizationId(orgY)).containsExactly(teamT);
        assertThat(teamOrgMembershipRepository.countByOrganizationIdAndStatus(
                orgX, TeamOrgMembershipEntity.Status.ACTIVE)).isEqualTo(1L);
        assertThat(teamOrgMembershipRepository.countByOrganizationIdAndStatus(
                orgY, TeamOrgMembershipEntity.Status.ACTIVE)).isEqualTo(1L);
    }

    @Test
    @DisplayName("AC-N06 親組織 ID の一括取得は DISTINCT で、同じ組織を2度返さない")
    void 親組織ID一括取得は重複しない() {
        // X に加盟するチームをもう1つ足し、(T→X)(U→X)(T→Y) の3行から X が2度出ないことを確かめる。
        String sfx = String.valueOf(System.nanoTime());
        Long teamU = insertTeam("N06 チームU " + sfx, "n06-u-" + sfx);
        linkTeamToOrganization(teamU, orgX, 3);
        em.flush();
        em.clear();

        List<Long> orgIds = teamOrgMembershipRepository.findDistinctOrganizationIdsByTeamIdIn(Set.of(teamT, teamU));

        assertThat(orgIds).containsExactlyInAnyOrder(orgX, orgY);
    }

    // =========================================================================
    // ヘルパー
    // =========================================================================

    private Long insertOrganization(String name, String slug) {
        em.createNativeQuery(
                        "INSERT INTO organizations (name, org_type, visibility, hierarchy_visibility, "
                                + "supporter_enabled, version, slug, created_at, updated_at) "
                                + "VALUES (:name, 'OTHER', 'PUBLIC', 'NONE', 1, 0, :slug, NOW(), NOW())")
                .setParameter("name", name)
                .setParameter("slug", slug)
                .executeUpdate();
        return ((Number) em.createNativeQuery("SELECT id FROM organizations WHERE slug = :slug")
                .setParameter("slug", slug)
                .getSingleResult()).longValue();
    }

    private Long insertTeam(String name, String slug) {
        em.createNativeQuery(
                        "INSERT INTO teams (name, visibility, supporter_enabled, version, member_count, slug, "
                                + "created_at, updated_at) "
                                + "VALUES (:name, 'PUBLIC', 1, 0, 0, :slug, NOW(), NOW())")
                .setParameter("name", name)
                .setParameter("slug", slug)
                .executeUpdate();
        return ((Number) em.createNativeQuery("SELECT id FROM teams WHERE slug = :slug")
                .setParameter("slug", slug)
                .getSingleResult()).longValue();
    }

    private Long insertUser(String email) {
        em.createNativeQuery(
                        "INSERT INTO users ("
                                + "email, last_name, first_name, display_name, status, "
                                + "is_searchable, handle_searchable, contact_approval_required, "
                                + "online_visibility, dm_receive_from, encryption_key_version, "
                                + "locale, timezone, reporting_restricted, follow_list_visibility, "
                                + "care_notification_enabled, offline_only, "
                                + "created_at, updated_at) "
                                + "VALUES (:email, 'N06', 'テスト', 'N06 テスト', 'ACTIVE', "
                                + "1, 1, 1, "
                                + "'NOBODY', 'ANYONE', 1, "
                                + "'ja', 'Asia/Tokyo', 0, 'PUBLIC', "
                                + "1, 0, "
                                + "NOW(), NOW())")
                .setParameter("email", email)
                .executeUpdate();
        return ((Number) em.createNativeQuery("SELECT id FROM users WHERE email = :email")
                .setParameter("email", email)
                .getSingleResult()).longValue();
    }

    private void insertTeamMember(Long userId, Long teamId) {
        em.createNativeQuery(
                        "INSERT INTO memberships (user_id, scope_type, scope_id, role_kind, joined_at, "
                                + "created_at, updated_at) "
                                + "VALUES (:uid, 'TEAM', :tid, 'MEMBER', NOW(), NOW(), NOW())")
                .setParameter("uid", userId)
                .setParameter("tid", teamId)
                .executeUpdate();
    }

    /** 書き込み API を経由せず ACTIVE な加盟行を直接作る（成立時刻は NOW() の {@code daysAgo} 日前）。 */
    private void linkTeamToOrganization(Long teamId, Long orgId, int daysAgo) {
        em.createNativeQuery(
                        "INSERT INTO team_org_memberships ("
                                + "team_id, organization_id, status, invited_at, responded_at, created_at) "
                                + "VALUES (:tid, :oid, 'ACTIVE', DATE_SUB(NOW(), INTERVAL :d DAY), "
                                + "DATE_SUB(NOW(), INTERVAL :d DAY), NOW())")
                .setParameter("tid", teamId)
                .setParameter("oid", orgId)
                .setParameter("d", daysAgo)
                .executeUpdate();
    }
}
