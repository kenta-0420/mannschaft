package com.mannschaft.app.social.service;

import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.common.CommonErrorCode;
import com.mannschaft.app.membership.domain.RoleKind;
import com.mannschaft.app.membership.domain.ScopeType;
import com.mannschaft.app.social.SocialErrorCode;
import com.mannschaft.app.social.dto.FollowTeamResponse;
import com.mannschaft.app.support.test.AbstractMySqlIntegrationTest;
import com.mannschaft.app.support.test.MembershipTestHelper;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.assertj.core.api.ThrowableAssert.ThrowingCallable;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.annotation.Transactional;

import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * CMP-261012-0315 フレンドチームのフォロー先 slug 指定 — 実 DB・実認可での試練（red）。
 *
 * <p>Service 単体テストは認可を Mock で置き換えるため、「どのロールが通るか」は本 IT でしか測れない。
 * 実 {@code AccessControlService} / {@code RoleService} と実 MySQL（Testcontainers）で、
 * ロール横断（AC-6/6b）とフォロー先の状態（AC-7）を数値経路・slug 経路の両方で固定する。</p>
 *
 * <h2>実装側が満たすべき契約</h2>
 * <ul>
 *   <li>{@code TeamFriendsService#followBySlug(Long teamId, String targetTeamSlug, Long userId)}。</li>
 *   <li>MEMBER・SUPPORTER・MANAGE_FRIEND_TEAMS 未付与の DEPUTY_ADMIN・非メンバー・他チームの ADMIN は
 *       403 COMMON_002、follows 行を作らない。権限グループで MANAGE_FRIEND_TEAMS を得た DEPUTY_ADMIN は成功。</li>
 *   <li>アーカイブ済み（archived_at あり・lifecycle_status は ACTIVE のまま）・PROVISIONED・論理削除済みの
 *       フォロー先は、数値・slug とも 404 SOCIAL_104、follows 行を作らない。</li>
 * </ul>
 */
@Transactional
@EnabledIf("com.mannschaft.app.support.test.AbstractMySqlIntegrationTest#isDockerAvailable")
@DisplayName("CMP-261012-0315 フレンドチーム slug 指定フォロー（実DB・ロール横断の試練）")
class TeamFriendFollowSlugIT extends AbstractMySqlIntegrationTest {

    private static final String PERMISSION = "MANAGE_FRIEND_TEAMS";
    private static final AtomicInteger SEQ = new AtomicInteger(0);

    @PersistenceContext
    private EntityManager em;

    @Autowired
    private TeamFriendsService teamFriendsService;

    /** 操作主体のチーム（フォロー元）。 */
    private Long teamId;
    /** フォロー先（ACTIVE・公開）。 */
    private Long targetId;
    private String targetSlug;
    /** 他テナント（無関係チーム）。 */
    private Long otherTeamId;

    @BeforeEach
    void setUp() {
        seedRolesAndPermission();
        teamId = insertTeam("ACTIVE", false, false).id();
        TeamRow target = insertTeam("ACTIVE", false, false);
        targetId = target.id();
        targetSlug = target.slug();
        otherTeamId = insertTeam("ACTIVE", false, false).id();
        em.flush();
        em.clear();
    }

    // =====================================================================
    // AC-1 / AC-5
    // =====================================================================

    @Nested
    @DisplayName("ADMIN の正常系")
    class AdminSuccess {

        @Test
        @DisplayName("AC-1: ADMIN が slug 指定で他チームをフォローでき、follows 行が1件できる")
        void ac1_slug指定でフォローできる() {
            Long admin = userWithRole("ADMIN", teamId);

            FollowTeamResponse res = teamFriendsService.followBySlug(teamId, targetSlug, admin);

            assertThat(res.getFollowedTeamId()).isEqualTo(targetId);
            assertThat(followCount(teamId, targetId)).isEqualTo(1L);
        }

        @Test
        @DisplayName("AC-5: ADMIN が数値 ID 指定で従来どおりフォローできる")
        void ac5_数値指定で従来どおりフォローできる() {
            Long admin = userWithRole("ADMIN", teamId);

            FollowTeamResponse res = teamFriendsService.follow(teamId, targetId, admin);

            assertThat(res.getFollowedTeamId()).isEqualTo(targetId);
            assertThat(followCount(teamId, targetId)).isEqualTo(1L);
        }

        @Test
        @DisplayName("AC-3: ADMIN が自チームの slug を指定すると 400 SOCIAL_101・行なし")
        void ac3_自チームslugは400() {
            Long admin = userWithRole("ADMIN", teamId);
            String ownSlug = slugOf(teamId);

            assertError(() -> teamFriendsService.followBySlug(teamId, ownSlug, admin),
                    SocialErrorCode.FRIEND_CANNOT_SELF_FOLLOW);
            assertThat(followCountFrom(teamId)).isZero();
        }

        @Test
        @DisplayName("AC-2: ADMIN が不在の slug を指定すると 404 SOCIAL_104・行なし")
        void ac2_不在slugは404() {
            Long admin = userWithRole("ADMIN", teamId);

            assertError(() -> teamFriendsService.followBySlug(teamId, "no-such-team-x", admin),
                    SocialErrorCode.FRIEND_TARGET_TEAM_NOT_FOUND);
            assertThat(followCountFrom(teamId)).isZero();
        }
    }

    // =====================================================================
    // AC-6 / AC-6b
    // =====================================================================

    @Nested
    @DisplayName("AC-6 / AC-6b: ロール横断（slug 経路・数値経路）")
    class RoleMatrix {

        @Test
        @DisplayName("AC-6: MEMBER は slug・数値とも 403 COMMON_002・行なし")
        void ac6_MEMBERは403() {
            Long member = insertUser();
            MembershipTestHelper.insertMembership(em, member, ScopeType.TEAM, teamId, RoleKind.MEMBER);
            assertForbiddenBothPaths(member);
        }

        @Test
        @DisplayName("AC-6: SUPPORTER は slug・数値とも 403 COMMON_002・行なし")
        void ac6_SUPPORTERは403() {
            Long supporter = insertUser();
            MembershipTestHelper.insertMembership(em, supporter, ScopeType.TEAM, teamId, RoleKind.SUPPORTER);
            assertForbiddenBothPaths(supporter);
        }

        @Test
        @DisplayName("AC-6: MANAGE_FRIEND_TEAMS を持たない DEPUTY_ADMIN は slug・数値とも 403 COMMON_002・行なし")
        void ac6_権限なしDEPUTY_ADMINは403() {
            Long deputy = userWithRole("DEPUTY_ADMIN", teamId);
            assertForbiddenBothPaths(deputy);
        }

        @Test
        @DisplayName("AC-6: 非メンバーは slug・数値とも 403 COMMON_002・行なし")
        void ac6_非メンバーは403() {
            Long outsider = insertUser();
            assertForbiddenBothPaths(outsider);
        }

        @Test
        @DisplayName("AC-6: 他テナント（別チームの ADMIN）は slug・数値とも 403 COMMON_002・行なし")
        void ac6_他テナントは403() {
            Long foreignAdmin = userWithRole("ADMIN", otherTeamId);
            assertForbiddenBothPaths(foreignAdmin);
        }

        @Test
        @DisplayName("AC-6b: 権限グループで MANAGE_FRIEND_TEAMS を得た DEPUTY_ADMIN は slug 指定で成功する")
        void ac6b_権限付きDEPUTY_ADMINは成功() {
            Long deputy = userWithRole("DEPUTY_ADMIN", teamId);
            Long group = insertPermissionGroup(teamId);
            addPermissionToGroup(group, PERMISSION);
            assignGroupToUser(deputy, group);
            em.flush();
            em.clear();

            FollowTeamResponse res = teamFriendsService.followBySlug(teamId, targetSlug, deputy);

            assertThat(res.getFollowedTeamId()).isEqualTo(targetId);
            assertThat(followCount(teamId, targetId)).isEqualTo(1L);
        }

        private void assertForbiddenBothPaths(Long actor) {
            em.flush();
            em.clear();
            assertError(() -> teamFriendsService.followBySlug(teamId, targetSlug, actor),
                    CommonErrorCode.COMMON_002);
            assertError(() -> teamFriendsService.follow(teamId, targetId, actor),
                    CommonErrorCode.COMMON_002);
            assertThat(followCountFrom(teamId)).as("403 ではフォロー行を作らない").isZero();
        }
    }

    // =====================================================================
    // AC-7
    // =====================================================================

    @Nested
    @DisplayName("AC-7: 利用できないフォロー先は数値・slug とも 404 SOCIAL_104")
    class UnavailableTarget {

        @Test
        @DisplayName("AC-7: アーカイブ済み（ACTIVE のまま archived_at あり）は数値・slug とも 404・行なし")
        void ac7_アーカイブ済みは404() {
            assertNotFoundBothPaths(insertTeam("ACTIVE", true, false));
        }

        @Test
        @DisplayName("AC-7: PROVISIONED（非ACTIVE）は数値・slug とも 404・行なし")
        void ac7_非ACTIVEは404() {
            assertNotFoundBothPaths(insertTeam("PROVISIONED", false, false));
        }

        @Test
        @DisplayName("AC-7: 論理削除済みは数値・slug とも 404・行なし")
        void ac7_論理削除済みは404() {
            assertNotFoundBothPaths(insertTeam("ACTIVE", false, true));
        }

        private void assertNotFoundBothPaths(TeamRow unavailable) {
            Long admin = userWithRole("ADMIN", teamId);
            em.flush();
            em.clear();

            assertError(() -> teamFriendsService.followBySlug(teamId, unavailable.slug(), admin),
                    SocialErrorCode.FRIEND_TARGET_TEAM_NOT_FOUND);
            assertError(() -> teamFriendsService.follow(teamId, unavailable.id(), admin),
                    SocialErrorCode.FRIEND_TARGET_TEAM_NOT_FOUND);
            assertThat(followCountFrom(teamId)).as("404 ではフォロー行を作らない").isZero();
        }
    }

    // =====================================================================
    // フィクスチャ
    // =====================================================================

    private record TeamRow(Long id, String slug) {}

    private static void assertError(ThrowingCallable call, Object expectedCode) {
        assertThatThrownBy(call)
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(expectedCode);
    }

    private long followCount(Long followerTeamId, Long followedTeamId) {
        return ((Number) em.createNativeQuery(
                        "SELECT COUNT(*) FROM follows WHERE follower_type = 'TEAM' AND follower_id = :f "
                                + "AND followed_type = 'TEAM' AND followed_id = :t")
                .setParameter("f", followerTeamId)
                .setParameter("t", followedTeamId)
                .getSingleResult()).longValue();
    }

    private long followCountFrom(Long followerTeamId) {
        return ((Number) em.createNativeQuery(
                        "SELECT COUNT(*) FROM follows WHERE follower_type = 'TEAM' AND follower_id = :f")
                .setParameter("f", followerTeamId)
                .getSingleResult()).longValue();
    }

    private String slugOf(Long id) {
        return (String) em.createNativeQuery("SELECT slug FROM teams WHERE id = :id")
                .setParameter("id", id).getSingleResult();
    }

    private void seedRolesAndPermission() {
        String[][] roles = {
                {"SYSTEM_ADMIN", "1"}, {"ADMIN", "2"}, {"DEPUTY_ADMIN", "3"},
                {"MEMBER", "4"}, {"SUPPORTER", "5"}, {"GUEST", "6"}};
        for (String[] r : roles) {
            em.createNativeQuery(
                            "INSERT IGNORE INTO roles (name, display_name, priority, is_system, created_at, updated_at) "
                                    + "VALUES (:name, :name, :priority, 1, NOW(), NOW())")
                    .setParameter("name", r[0])
                    .setParameter("priority", Integer.parseInt(r[1]))
                    .executeUpdate();
        }
        em.createNativeQuery(
                        "INSERT INTO permissions (name, display_name, scope, created_at, updated_at) "
                                + "SELECT :perm, 'フレンドチーム管理', 'TEAM', NOW(), NOW() FROM DUAL "
                                + "WHERE NOT EXISTS (SELECT 1 FROM permissions WHERE name = :perm)")
                .setParameter("perm", PERMISSION)
                .executeUpdate();
        // ADMIN には既定付与（本番の role_permissions と同じ is_default=1）
        em.createNativeQuery(
                        "INSERT INTO role_permissions (role_id, permission_id, is_default, created_at) "
                                + "SELECT r.id, p.id, 1, NOW() FROM roles r CROSS JOIN permissions p "
                                + "WHERE r.name = 'ADMIN' AND p.name = :perm "
                                + "AND NOT EXISTS (SELECT 1 FROM role_permissions rp "
                                + "                WHERE rp.role_id = r.id AND rp.permission_id = p.id)")
                .setParameter("perm", PERMISSION)
                .executeUpdate();
        em.flush();
    }

    private Long insertUser() {
        int n = SEQ.incrementAndGet();
        String email = "cmp0315-follow-" + n + "-" + System.nanoTime() + "@example.com";
        em.createNativeQuery(
                        "INSERT INTO users (email, last_name, first_name, display_name, status, "
                                + "is_searchable, handle_searchable, contact_approval_required, "
                                + "online_visibility, dm_receive_from, encryption_key_version, "
                                + "locale, timezone, reporting_restricted, follow_list_visibility, "
                                + "care_notification_enabled, offline_only, created_at, updated_at) "
                                + "VALUES (:email, '試験', :fn, :dn, 'ACTIVE', 1, 1, 1, "
                                + "'NOBODY', 'ANYONE', 1, 'ja', 'Asia/Tokyo', 0, 'PUBLIC', 1, 0, NOW(), NOW())")
                .setParameter("email", email)
                .setParameter("fn", "利用者" + n)
                .setParameter("dn", "試験 利用者" + n)
                .executeUpdate();
        return ((Number) em.createNativeQuery("SELECT id FROM users WHERE email = :email")
                .setParameter("email", email).getSingleResult()).longValue();
    }

    /** user_roles（ADMIN / DEPUTY_ADMIN）＋在籍 memberships を張ったユーザーを作る。 */
    private Long userWithRole(String roleName, Long scopeTeamId) {
        Long uid = insertUser();
        em.createNativeQuery(
                        "INSERT INTO user_roles (user_id, role_id, team_id, organization_id, created_at, updated_at) "
                                + "SELECT :uid, r.id, :tid, NULL, NOW(), NOW() FROM roles r WHERE r.name = :role")
                .setParameter("uid", uid)
                .setParameter("tid", scopeTeamId)
                .setParameter("role", roleName)
                .executeUpdate();
        MembershipTestHelper.insertMembership(em, uid, ScopeType.TEAM, scopeTeamId, RoleKind.MEMBER);
        em.flush();
        return uid;
    }

    private TeamRow insertTeam(String lifecycleStatus, boolean archived, boolean deleted) {
        int n = SEQ.incrementAndGet();
        String slug = "cmp0315-t" + n + "-" + Long.toString(System.nanoTime() % 1_000_000L, 36);
        em.createNativeQuery(
                        "INSERT INTO teams (name, visibility, supporter_enabled, version, member_count, slug, "
                                + "lifecycle_status, archived_at, deleted_at, created_at, updated_at) "
                                + "VALUES (:name, 'PUBLIC', 1, 0, 0, :slug, :ls, "
                                + (archived ? "NOW()" : "NULL") + ", "
                                + (deleted ? "NOW()" : "NULL") + ", NOW(), NOW())")
                .setParameter("name", "CMP0315チーム" + n)
                .setParameter("slug", slug)
                .setParameter("ls", lifecycleStatus)
                .executeUpdate();
        Long id = ((Number) em.createNativeQuery("SELECT id FROM teams WHERE slug = :slug")
                .setParameter("slug", slug).getSingleResult()).longValue();
        return new TeamRow(id, slug);
    }

    private Long insertPermissionGroup(Long scopeTeamId) {
        String name = "CMP0315権限束" + SEQ.incrementAndGet();
        em.createNativeQuery(
                        "INSERT INTO permission_groups (team_id, organization_id, target_role, name, "
                                + "created_at, updated_at) VALUES (:tid, NULL, 'DEPUTY_ADMIN', :name, NOW(), NOW())")
                .setParameter("tid", scopeTeamId)
                .setParameter("name", name)
                .executeUpdate();
        em.flush();
        return ((Number) em.createNativeQuery("SELECT id FROM permission_groups WHERE name = :name")
                .setParameter("name", name).getSingleResult()).longValue();
    }

    private void addPermissionToGroup(Long groupId, String permissionName) {
        em.createNativeQuery(
                        "INSERT INTO permission_group_permissions (group_id, permission_id, created_at) "
                                + "SELECT :gid, p.id, NOW() FROM permissions p WHERE p.name = :perm")
                .setParameter("gid", groupId)
                .setParameter("perm", permissionName)
                .executeUpdate();
        em.flush();
    }

    private void assignGroupToUser(Long userId, Long groupId) {
        em.createNativeQuery(
                        "INSERT INTO user_permission_groups (user_id, group_id, created_at) VALUES (:uid, :gid, NOW())")
                .setParameter("uid", userId)
                .setParameter("gid", groupId)
                .executeUpdate();
        em.flush();
    }
}
