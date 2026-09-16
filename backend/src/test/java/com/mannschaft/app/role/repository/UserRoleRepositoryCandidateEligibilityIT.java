package com.mannschaft.app.role.repository;

import com.mannschaft.app.membership.domain.RoleKind;
import com.mannschaft.app.membership.domain.ScopeType;
import com.mannschaft.app.support.test.AbstractMySqlIntegrationTest;
import com.mannschaft.app.support.test.MembershipTestHelper;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 柱①「ADMINゼロ根治」検分反映（P1-2） — {@link UserRoleRepository} の
 * 承継候補選定クエリ（DEPUTY_ADMIN / MEMBER）が §11.2 の候補資格を実 SQL レベルで
 * 満たすことを検証する repository IT。
 *
 * <p>正本: docs/architecture/account_purge_last_admin_succession.md §11.2 / §12.6。
 * 候補資格: 現役在籍（{@code memberships} が active）／退会予定でない
 * （{@code users.deleted_at IS NULL}）／匿名化済・利用停止中でない
 * （{@code users.status = 'ACTIVE'}）。</p>
 */
@Transactional
@EnabledIf("com.mannschaft.app.support.test.AbstractMySqlIntegrationTest#isDockerAvailable")
@DisplayName("柱①ADMINゼロ根治 P1-2 承継候補SQLの資格条件契約")
class UserRoleRepositoryCandidateEligibilityIT extends AbstractMySqlIntegrationTest {

    @Autowired
    private UserRoleRepository userRoleRepository;

    @PersistenceContext
    private EntityManager em;

    private Long teamId;

    private Long activeDeputyId;
    private Long deletedDeputyId;
    private Long frozenDeputyId;
    private Long activeMemberId;
    private Long deletedMemberId;
    private Long nonMemberDeputyId; // DEPUTY_ADMINロールは持つがmembershipsが非active

    @BeforeEach
    void setUp() {
        String slug = "urce-team-" + Long.toHexString(System.nanoTime());
        teamId = insertTeam("URCE チーム", slug);

        activeDeputyId = insertUser("urce-active-deputy-" + System.nanoTime() + "@example.com", "ACTIVE", false);
        deletedDeputyId = insertUser("urce-deleted-deputy-" + System.nanoTime() + "@example.com", "ACTIVE", true);
        frozenDeputyId = insertUser("urce-frozen-deputy-" + System.nanoTime() + "@example.com", "FROZEN", false);
        activeMemberId = insertUser("urce-active-member-" + System.nanoTime() + "@example.com", "ACTIVE", false);
        deletedMemberId = insertUser("urce-deleted-member-" + System.nanoTime() + "@example.com", "ACTIVE", true);
        nonMemberDeputyId = insertUser("urce-nonmember-deputy-" + System.nanoTime() + "@example.com", "ACTIVE", false);

        // DEPUTY_ADMIN候補: 現役+在籍のみ資格あり
        MembershipTestHelper.insertMembership(em, activeDeputyId, ScopeType.TEAM, teamId, RoleKind.MEMBER);
        MembershipTestHelper.insertUserRole(em, activeDeputyId, "DEPUTY_ADMIN", teamId, null);

        MembershipTestHelper.insertMembership(em, deletedDeputyId, ScopeType.TEAM, teamId, RoleKind.MEMBER);
        MembershipTestHelper.insertUserRole(em, deletedDeputyId, "DEPUTY_ADMIN", teamId, null);

        MembershipTestHelper.insertMembership(em, frozenDeputyId, ScopeType.TEAM, teamId, RoleKind.MEMBER);
        MembershipTestHelper.insertUserRole(em, frozenDeputyId, "DEPUTY_ADMIN", teamId, null);

        // DEPUTY_ADMINロールは持つが membership が無い（在籍実態なし）
        MembershipTestHelper.insertUserRole(em, nonMemberDeputyId, "DEPUTY_ADMIN", teamId, null);

        // MEMBER候補
        MembershipTestHelper.insertMembership(em, activeMemberId, ScopeType.TEAM, teamId, RoleKind.MEMBER);
        MembershipTestHelper.insertMembership(em, deletedMemberId, ScopeType.TEAM, teamId, RoleKind.MEMBER);

        em.flush();
        em.clear();
    }

    @Test
    @DisplayName("P1-2: findDeputyAdminCandidateIdsByTeamは現役かつ在籍のDEPUTY_ADMINのみ返す")
    void DEPUTY候補は現役かつ在籍のみ返る() {
        List<Long> candidates = userRoleRepository.findDeputyAdminCandidateIdsByTeam(teamId);

        assertThat(candidates).contains(activeDeputyId);
        assertThat(candidates).doesNotContain(deletedDeputyId, frozenDeputyId, nonMemberDeputyId);
    }

    @Test
    @DisplayName("P1-2: findMemberCandidateIdsByTeamは現役かつ在籍のMEMBERのみ返す")
    void MEMBER候補は現役かつ在籍のみ返る() {
        List<Long> candidates = userRoleRepository.findMemberCandidateIdsByTeam(teamId);

        assertThat(candidates).contains(activeMemberId);
        assertThat(candidates).doesNotContain(deletedMemberId);
    }

    private Long insertUser(String email, String status, boolean deleted) {
        em.createNativeQuery(
                        "INSERT INTO users ("
                                + "email, last_name, first_name, display_name, status, "
                                + "is_searchable, handle_searchable, contact_approval_required, "
                                + "online_visibility, dm_receive_from, encryption_key_version, "
                                + "locale, timezone, reporting_restricted, follow_list_visibility, "
                                + "care_notification_enabled, offline_only, deleted_at, "
                                + "created_at, updated_at) "
                                + "VALUES (:email, 'URCE', 'テスト', 'URCE テスト', :status, "
                                + "1, 1, 1, "
                                + "'NOBODY', 'ANYONE', 1, "
                                + "'ja', 'Asia/Tokyo', 0, 'PUBLIC', "
                                + "1, 0, " + (deleted ? "NOW()" : "NULL") + ", "
                                + "NOW(), NOW())")
                .setParameter("email", email)
                .setParameter("status", status)
                .executeUpdate();
        return ((Number) em.createNativeQuery("SELECT id FROM users WHERE email = :email")
                .setParameter("email", email)
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
}
