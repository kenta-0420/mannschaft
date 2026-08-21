package com.mannschaft.app.recruitment.visibility;

import com.mannschaft.app.common.visibility.ContentVisibilityChecker;
import com.mannschaft.app.common.visibility.ReferenceType;
import com.mannschaft.app.recruitment.entity.RecruitmentFriendTargetEntity;
import com.mannschaft.app.recruitment.repository.RecruitmentFriendTargetRepository;
import com.mannschaft.app.support.test.AbstractMySqlIntegrationTest;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * F00 Phase C — {@link RecruitmentListingVisibilityResolver} 結合テスト。
 *
 * <p>実 MySQL（Testcontainers）に対し最小限の seed を投入し、Resolver と
 * {@link ContentVisibilityChecker} を組み立てた上で
 * status × visibility × メンバーシップ の各 case を E2E に検証する。
 *
 * <p>セットアップは {@code EventVisibilityResolverIntegrationTest} の方式を踏襲。
 * すなわち {@code @Transactional} ロールバック方式 + {@code em.createNativeQuery}
 * で users / organizations / teams / team_org_memberships / roles / user_roles /
 * recruitment_listings を直接 INSERT する。
 *
 * <p>recruitment_categories は Flyway 初期データで {@code id=1} (futsal_open) が
 * 既に投入されているのでそれを利用する。
 */
@Transactional
@EnabledIf("com.mannschaft.app.support.test.AbstractMySqlIntegrationTest#isDockerAvailable")
@DisplayName("RecruitmentListingVisibilityResolver 結合テスト")
class RecruitmentListingVisibilityResolverIntegrationTest extends AbstractMySqlIntegrationTest {

    @Autowired
    private ContentVisibilityChecker checker;

    @Autowired
    private RecruitmentFriendTargetRepository friendTargetRepository;

    @PersistenceContext
    private EntityManager em;

    private Long memberRoleId;
    private Long systemAdminRoleId;
    private Long memberUserId;
    private Long nonMemberUserId;
    private Long sysAdminUserId;
    private Long teamId;
    private Long orgId;
    private Long categoryId;

    // F22.1 市 FRIEND_TEAMS_ONLY 用の追加 seed。
    private Long friendTeamId;
    private Long friendMemberUserId;

    @BeforeEach
    void setUp() {
        // 1. ロールを直接投入
        // 冪等化: roles はグローバル参照テーブルのため INSERT IGNORE で二重INSERTを無害化する
        // （同一 name の重複INSERTは UNIQUE 制約違反になる。CI shard 再編成で同一 JVM 内の
        // 同居テストが変わり得るため、盲目的 INSERT は禁止。既存行があれば黙って再利用する）。
        em.createNativeQuery(
                "INSERT IGNORE INTO roles (name, display_name, priority, is_system, created_at, updated_at) "
                        + "VALUES ('SYSTEM_ADMIN', 'システム管理者', 1, 1, NOW(), NOW())")
                .executeUpdate();
        em.createNativeQuery(
                "INSERT IGNORE INTO roles (name, display_name, priority, is_system, created_at, updated_at) "
                        + "VALUES ('MEMBER', 'メンバー', 4, 0, NOW(), NOW())")
                .executeUpdate();
        em.flush();

        memberRoleId = ((Number) em.createNativeQuery(
                "SELECT id FROM roles WHERE name = 'MEMBER'").getSingleResult()).longValue();
        systemAdminRoleId = ((Number) em.createNativeQuery(
                "SELECT id FROM roles WHERE name = 'SYSTEM_ADMIN'").getSingleResult()).longValue();

        memberUserId    = insertUser("rl.member@example.com", "山田", "太郎");
        nonMemberUserId = insertUser("rl.nonmember@example.com", "鈴木", "花子");
        sysAdminUserId  = insertUser("rl.sysadmin@example.com", "管理", "者");

        orgId  = insertOrganization("RL結合 組織");
        teamId = insertTeam("RL結合 チーム");
        insertTeamOrgMembership(teamId, orgId);

        insertUserRole(memberUserId, memberRoleId, teamId, null);
        insertUserRole(sysAdminUserId, systemAdminRoleId, null, null);

        // F22.1 市: フレンドチームとそのメンバーを seed（FRIEND_TEAMS_ONLY 検証用）。
        friendTeamId = insertTeam("RL結合 フレンドチーム");
        friendMemberUserId = insertUser("rl.friend@example.com", "友達", "一郎");
        insertUserRole(friendMemberUserId, memberRoleId, friendTeamId, null);
        // 札主チーム ↔ フレンドチームの成立フレンド関係（team_a_id < team_b_id で正規化）。
        insertTeamFriend(teamId, friendTeamId);

        // ddl-auto=create-drop の test 環境では Flyway シードが走らないため
        // テストヘルパーで futsal_open カテゴリを直接 INSERT する。
        em.createNativeQuery(
                "INSERT INTO recruitment_categories ("
                        + "code, name_i18n_key, default_participation_type, "
                        + "display_order, is_active, created_at, updated_at) "
                        + "VALUES ('futsal_open', 'recruitment.category.futsal_open', "
                        + "'INDIVIDUAL', 1, 1, NOW(), NOW())")
                .executeUpdate();
        categoryId = ((Number) em.createNativeQuery(
                "SELECT id FROM recruitment_categories WHERE code = 'futsal_open'")
                .getSingleResult()).longValue();

        em.flush();
        em.clear();
    }

    private Long insertUser(String email, String lastName, String firstName) {
        em.createNativeQuery(
                "INSERT INTO users ("
                        + "email, last_name, first_name, display_name, status, "
                        + "is_searchable, handle_searchable, contact_approval_required, "
                        + "online_visibility, dm_receive_from, encryption_key_version, "
                        + "locale, timezone, reporting_restricted, follow_list_visibility, "
                        + "care_notification_enabled, offline_only, "
                        + "created_at, updated_at) "
                        + "VALUES (:email, :ln, :fn, :dn, 'ACTIVE', "
                        + "1, 1, 1, "
                        + "'NOBODY', 'ANYONE', 1, "
                        + "'ja', 'Asia/Tokyo', 0, 'PUBLIC', "
                        + "1, 0, "
                        + "NOW(), NOW())")
                .setParameter("email", email)
                .setParameter("ln", lastName)
                .setParameter("fn", firstName)
                .setParameter("dn", lastName + " " + firstName)
                .executeUpdate();
        return ((Number) em.createNativeQuery(
                "SELECT id FROM users WHERE email = :email")
                .setParameter("email", email)
                .getSingleResult()).longValue();
    }

    private Long insertOrganization(String name) {
        em.createNativeQuery(
                "INSERT INTO organizations (name, org_type, visibility, hierarchy_visibility, "
                        + "supporter_enabled, version, slug, created_at, updated_at) "
                        + "VALUES (:name, 'OTHER', 'PUBLIC', 'NONE', 1, 0, CONCAT('s-', LEFT(REPLACE(UUID(),'-',''),8)), NOW(), NOW())")
                .setParameter("name", name)
                .executeUpdate();
        return ((Number) em.createNativeQuery(
                "SELECT id FROM organizations WHERE name = :name")
                .setParameter("name", name)
                .getSingleResult()).longValue();
    }

    private Long insertTeam(String name) {
        em.createNativeQuery(
                "INSERT INTO teams (name, visibility, supporter_enabled, version, member_count, slug, created_at, updated_at) "
                        + "VALUES (:name, 'PUBLIC', 1, 0, 0, CONCAT('s-', LEFT(REPLACE(UUID(),'-',''),8)), NOW(), NOW())")
                .setParameter("name", name)
                .executeUpdate();
        return ((Number) em.createNativeQuery(
                "SELECT id FROM teams WHERE name = :name")
                .setParameter("name", name)
                .getSingleResult()).longValue();
    }

    private void insertTeamOrgMembership(Long teamId, Long orgId) {
        em.createNativeQuery(
                "INSERT INTO team_org_memberships (team_id, organization_id, status, invited_at, created_at) "
                        + "VALUES (:tid, :oid, 'ACTIVE', NOW(), NOW())")
                .setParameter("tid", teamId)
                .setParameter("oid", orgId)
                .executeUpdate();
    }

    private void insertUserRole(Long uid, Long roleId, Long teamIdParam, Long orgIdParam) {
        em.createNativeQuery(
                "INSERT INTO user_roles (user_id, role_id, team_id, organization_id, created_at, updated_at) "
                        + "VALUES (:uid, :rid, :tid, :oid, NOW(), NOW())")
                .setParameter("uid", uid)
                .setParameter("rid", roleId)
                .setParameter("tid", teamIdParam)
                .setParameter("oid", orgIdParam)
                .executeUpdate();
    }

    /**
     * recruitment_listings テーブルへ最小限の募集枠行を直接 INSERT する。
     *
     * <p>NOT NULL 列・CHECK 制約を満たすように全列を明示する。
     * deadline / auto_cancel_at は start_at より前、auto_cancel_at は deadline 以下に設定。
     *
     * @return 生成された listing_id
     */
    private Long insertRecruitment(String title, Long createdBy, String status, String visibility) {
        LocalDateTime now = LocalDateTime.now();
        LocalDateTime startAt = now.plusDays(7);
        LocalDateTime endAt = startAt.plusHours(2);
        LocalDateTime deadline = startAt.minusDays(1);
        LocalDateTime autoCancel = deadline.minusHours(1);

        em.createNativeQuery(
                "INSERT INTO recruitment_listings ("
                        + "scope_type, scope_id, category_id, title, "
                        + "participation_type, "
                        + "start_at, end_at, application_deadline, auto_cancel_at, "
                        + "capacity, min_capacity, "
                        + "confirmed_count, waitlist_count, waitlist_max, "
                        + "payment_enabled, "
                        + "visibility, status, "
                        + "created_by, "
                        + "participant_count_cache, next_waitlist_position, "
                        + "created_at, updated_at) "
                        + "VALUES ('TEAM', :scopeId, :categoryId, :title, "
                        + "'INDIVIDUAL', "
                        + ":startAt, :endAt, :deadline, :autoCancel, "
                        + "10, 1, "
                        + "0, 0, 100, "
                        + "0, "
                        + ":visibility, :status, "
                        + ":createdBy, "
                        + "0, 1, "
                        + "NOW(), NOW())")
                .setParameter("scopeId", teamId)
                .setParameter("categoryId", categoryId)
                .setParameter("title", title)
                .setParameter("startAt", startAt)
                .setParameter("endAt", endAt)
                .setParameter("deadline", deadline)
                .setParameter("autoCancel", autoCancel)
                .setParameter("visibility", visibility)
                .setParameter("status", status)
                .setParameter("createdBy", createdBy)
                .executeUpdate();
        return ((Number) em.createNativeQuery(
                "SELECT id FROM recruitment_listings WHERE title = :title")
                .setParameter("title", title)
                .getSingleResult()).longValue();
    }

    /** 札主チーム ↔ 宛先チームの成立フレンド関係を直接 INSERT する（team_a_id < team_b_id 正規化）。 */
    private void insertTeamFriend(Long teamX, Long teamY) {
        Long a = Math.min(teamX, teamY);
        Long b = Math.max(teamX, teamY);
        em.createNativeQuery(
                "INSERT INTO team_friends ("
                        + "team_a_id, team_b_id, established_at, a_follow_id, b_follow_id, "
                        + "is_public, created_at, updated_at) "
                        + "VALUES (:a, :b, NOW(), 0, 0, 0, NOW(), NOW())")
                .setParameter("a", a)
                .setParameter("b", b)
                .executeUpdate();
    }

    /** 札に TEAM 粒度のフレンド宛先を 1 件追加する（recruitment_friend_targets）。 */
    private void insertFriendTargetTeam(Long listingId, Long targetTeamId) {
        friendTargetRepository.save(
                RecruitmentFriendTargetEntity.ofTeam(listingId, targetTeamId));
    }

    // =========================================================================
    // シナリオ
    // =========================================================================

    @Test
    @DisplayName("PUBLIC × OPEN は匿名・非メンバー・メンバーすべて閲覧可")
    void public_open_visible_to_all() {
        Long id = insertRecruitment("rl-public-open", memberUserId, "OPEN", "PUBLIC");
        em.flush();
        em.clear();

        assertThat(checker.canView(ReferenceType.RECRUITMENT_LISTING, id, null)).isTrue();
        assertThat(checker.canView(ReferenceType.RECRUITMENT_LISTING, id, nonMemberUserId)).isTrue();
        assertThat(checker.canView(ReferenceType.RECRUITMENT_LISTING, id, memberUserId)).isTrue();
    }

    @Test
    @DisplayName("SCOPE_ONLY × OPEN は所属メンバーのみ閲覧可")
    void scope_only_open_visible_to_member_only() {
        Long id = insertRecruitment("rl-scope-open", memberUserId, "OPEN", "SCOPE_ONLY");
        em.flush();
        em.clear();

        assertThat(checker.canView(ReferenceType.RECRUITMENT_LISTING, id, null)).isFalse();
        assertThat(checker.canView(ReferenceType.RECRUITMENT_LISTING, id, nonMemberUserId)).isFalse();
        assertThat(checker.canView(ReferenceType.RECRUITMENT_LISTING, id, memberUserId)).isTrue();
    }

    @Test
    @DisplayName("DRAFT は作成者本人および SystemAdmin のみ閲覧可")
    void draft_visible_to_author_or_sysadmin_only() {
        Long id = insertRecruitment("rl-draft", memberUserId, "DRAFT", "PUBLIC");
        em.flush();
        em.clear();

        assertThat(checker.canView(ReferenceType.RECRUITMENT_LISTING, id, null)).isFalse();
        assertThat(checker.canView(ReferenceType.RECRUITMENT_LISTING, id, nonMemberUserId)).isFalse();
        assertThat(checker.canView(ReferenceType.RECRUITMENT_LISTING, id, memberUserId)).isTrue();
        assertThat(checker.canView(ReferenceType.RECRUITMENT_LISTING, id, sysAdminUserId)).isTrue();
    }

    @Test
    @DisplayName("CANCELLED は SystemAdmin のみ閲覧可（ARCHIVED 扱い）")
    void cancelled_visible_to_sysadmin_only() {
        Long id = insertRecruitment("rl-cancelled", memberUserId, "CANCELLED", "PUBLIC");
        em.flush();
        em.clear();

        assertThat(checker.canView(ReferenceType.RECRUITMENT_LISTING, id, null)).isFalse();
        assertThat(checker.canView(ReferenceType.RECRUITMENT_LISTING, id, memberUserId)).isFalse();
        assertThat(checker.canView(ReferenceType.RECRUITMENT_LISTING, id, nonMemberUserId)).isFalse();
        assertThat(checker.canView(ReferenceType.RECRUITMENT_LISTING, id, sysAdminUserId)).isTrue();
    }

    @Test
    @DisplayName("AUTO_CANCELLED は SystemAdmin のみ閲覧可")
    void auto_cancelled_visible_to_sysadmin_only() {
        Long id = insertRecruitment("rl-auto-cancelled", memberUserId, "AUTO_CANCELLED", "PUBLIC");
        em.flush();
        em.clear();

        assertThat(checker.canView(ReferenceType.RECRUITMENT_LISTING, id, memberUserId)).isFalse();
        assertThat(checker.canView(ReferenceType.RECRUITMENT_LISTING, id, sysAdminUserId)).isTrue();
    }

    @Test
    @DisplayName("不存在 ID は誰に対しても false")
    void unknown_id_false() {
        assertThat(checker.canView(ReferenceType.RECRUITMENT_LISTING, 999_999L, memberUserId)).isFalse();
        assertThat(checker.canView(ReferenceType.RECRUITMENT_LISTING, 999_999L, sysAdminUserId)).isFalse();
    }

    @Test
    @DisplayName("filterAccessible は SCOPE_ONLY と PUBLIC を所属メンバー視点で正しくフィルタ")
    void filterAccessible_mixed_visibility_for_member() {
        Long id1 = insertRecruitment("rl-flt-1", memberUserId, "OPEN", "PUBLIC");
        Long id2 = insertRecruitment("rl-flt-2", memberUserId, "OPEN", "SCOPE_ONLY");
        Long id3 = insertRecruitment("rl-flt-3", memberUserId, "DRAFT", "PUBLIC");
        em.flush();
        em.clear();

        Set<Long> nonMember = checker.filterAccessible(
                ReferenceType.RECRUITMENT_LISTING, List.of(id1, id2, id3), nonMemberUserId);
        assertThat(nonMember).containsExactly(id1);

        Set<Long> member = checker.filterAccessible(
                ReferenceType.RECRUITMENT_LISTING, List.of(id1, id2, id3), memberUserId);
        // id3 (DRAFT) は author 自身なので可視
        assertThat(member).containsExactlyInAnyOrder(id1, id2, id3);

        Set<Long> sysAdmin = checker.filterAccessible(
                ReferenceType.RECRUITMENT_LISTING, List.of(id1, id2, id3), sysAdminUserId);
        assertThat(sysAdmin).containsExactlyInAnyOrder(id1, id2, id3);
    }

    // =========================================================================
    // F22.1 市: FRIEND_TEAMS_ONLY（CUSTOM 正規化 → evaluateCustom）
    //   🔴-2 根治の回帰テスト（02_api_design §7 / 04_security §1.1）
    // =========================================================================

    @Test
    @DisplayName("FRIEND_TEAMS_ONLY: 宛先フレンドチームのメンバーは閲覧可")
    void friendTeamsOnly_friendMember_visible() {
        Long id = insertRecruitment("rl-friend-1", memberUserId, "OPEN", "FRIEND_TEAMS_ONLY");
        insertFriendTargetTeam(id, friendTeamId);
        em.flush();
        em.clear();

        assertThat(checker.canView(ReferenceType.RECRUITMENT_LISTING, id, friendMemberUserId))
                .as("宛先フレンドチームのメンバーは閲覧可")
                .isTrue();
    }

    @Test
    @DisplayName("FRIEND_TEAMS_ONLY: 札主チーム自身のメンバーは閲覧可")
    void friendTeamsOnly_ownerMember_visible() {
        Long id = insertRecruitment("rl-friend-2", memberUserId, "OPEN", "FRIEND_TEAMS_ONLY");
        insertFriendTargetTeam(id, friendTeamId);
        em.flush();
        em.clear();

        assertThat(checker.canView(ReferenceType.RECRUITMENT_LISTING, id, memberUserId))
                .as("札主チーム自身のメンバーは閲覧可")
                .isTrue();
    }

    @Test
    @DisplayName("FRIEND_TEAMS_ONLY: 第三者（非宛先・非札主）は閲覧不可（404 存在秘匿）")
    void friendTeamsOnly_thirdParty_invisible() {
        Long id = insertRecruitment("rl-friend-3", memberUserId, "OPEN", "FRIEND_TEAMS_ONLY");
        insertFriendTargetTeam(id, friendTeamId);
        em.flush();
        em.clear();

        // nonMemberUserId はどのチームにも所属しない第三者。
        assertThat(checker.canView(ReferenceType.RECRUITMENT_LISTING, id, nonMemberUserId))
                .as("第三者は閲覧不可")
                .isFalse();
        // 未ログインも不可。
        assertThat(checker.canView(ReferenceType.RECRUITMENT_LISTING, id, null))
                .as("未ログインは閲覧不可")
                .isFalse();
    }

    @Test
    @DisplayName("FRIEND_TEAMS_ONLY: 宛先指定が無ければ札主以外は閲覧不可（fail-closed）")
    void friendTeamsOnly_noTarget_onlyOwnerVisible() {
        Long id = insertRecruitment("rl-friend-4", memberUserId, "OPEN", "FRIEND_TEAMS_ONLY");
        // 宛先を一切登録しない。
        em.flush();
        em.clear();

        // 宛先未登録でもフレンドチームメンバーは対象外。
        assertThat(checker.canView(ReferenceType.RECRUITMENT_LISTING, id, friendMemberUserId))
                .as("宛先未登録ならフレンドメンバーも不可")
                .isFalse();
        // 札主チームメンバーは常に可。
        assertThat(checker.canView(ReferenceType.RECRUITMENT_LISTING, id, memberUserId))
                .as("札主チームメンバーは可")
                .isTrue();
    }
}
