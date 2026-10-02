package com.mannschaft.app.chat.visibility;

import com.mannschaft.app.common.visibility.ContentVisibilityChecker;
import com.mannschaft.app.common.visibility.ReferenceType;
import com.mannschaft.app.support.test.AbstractMySqlIntegrationTest;
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
 * F00 Phase B（積み残し根治） — {@link ChatMessageVisibilityResolver} 結合テスト。
 *
 * <p>実機 E2E で「問い合わせ通知が visibility deny で一件も作成されない」障害を捕捉した根治。
 * チャットは visibility 概念無しの最小実装（§12.3.1）＝ SCOPE_AFFILIATED 固定であり、メッセージが
 * 属するチャンネルの scope（TEAM/ORGANIZATION）直接所属者が閲覧可・非所属は不可・DM 等スコープ無しは
 * fail-closed・論理削除は不可視・SystemAdmin 高速パスは可視、を実 MySQL で検証する。</p>
 *
 * <p>本 IT は Resolver 用 JPQL Projection（{@code chat_messages} × {@code chat_channels} 結合で
 * scope 導出）を実 DB で通す唯一の関門でもある。必ず {@link ContentVisibilityChecker} 経由で呼び出す
 * （設計書 §15 D-16 / 殿の指示）。セットアップは {@code BulletinThreadVisibilityResolverIntegrationTest}
 * を踏襲する。</p>
 */
@Transactional
@EnabledIf("com.mannschaft.app.support.test.AbstractMySqlIntegrationTest#isDockerAvailable")
@DisplayName("ChatMessageVisibilityResolver 結合テスト")
class ChatMessageVisibilityResolverIntegrationTest extends AbstractMySqlIntegrationTest {

    @Autowired
    private ContentVisibilityChecker checker;

    @PersistenceContext
    private EntityManager em;

    private Long memberRoleId;
    private Long systemAdminRoleId;
    private Long adminRoleId;
    private Long deputyAdminRoleId;
    private Long memberUserId;
    private Long nonMemberUserId;
    private Long sysAdminUserId;
    private Long teamAdminUserId;
    private Long deputyAdminUserId;
    private Long teamId;
    private Long orgId;

    @BeforeEach
    void setUp() {
        // 冪等化: insertRoleIfAbsent 参照（存在確認してから INSERT。INSERT IGNORE は使用禁止）
        insertRoleIfAbsent("SYSTEM_ADMIN", "システム管理者", 1, true);
        insertRoleIfAbsent("MEMBER", "メンバー", 4, false);
        insertRoleIfAbsent("ADMIN", "管理者", 2, false);
        insertRoleIfAbsent("DEPUTY_ADMIN", "副管理者", 3, false);
        em.flush();

        memberRoleId = ((Number) em.createNativeQuery(
                "SELECT id FROM roles WHERE name = 'MEMBER'").getSingleResult()).longValue();
        systemAdminRoleId = ((Number) em.createNativeQuery(
                "SELECT id FROM roles WHERE name = 'SYSTEM_ADMIN'").getSingleResult()).longValue();
        adminRoleId = ((Number) em.createNativeQuery(
                "SELECT id FROM roles WHERE name = 'ADMIN'").getSingleResult()).longValue();
        deputyAdminRoleId = ((Number) em.createNativeQuery(
                "SELECT id FROM roles WHERE name = 'DEPUTY_ADMIN'").getSingleResult()).longValue();

        memberUserId    = insertUser("chatvis.member@example.com", "山田", "太郎");
        nonMemberUserId = insertUser("chatvis.nonmember@example.com", "鈴木", "花子");
        sysAdminUserId  = insertUser("chatvis.sysadmin@example.com", "管理", "者");
        teamAdminUserId = insertUser("chatvis.teamadmin@example.com", "佐藤", "隊長");
        deputyAdminUserId = insertUser("chatvis.deputy@example.com", "田中", "副長");

        orgId  = insertOrganization("CHATVIS 組織");
        teamId = insertTeam("CHATVIS チーム");
        insertTeamOrgMembership(teamId, orgId);

        insertUserRole(memberUserId, memberRoleId, teamId, null);
        insertUserRole(sysAdminUserId, systemAdminRoleId, null, null);
        insertUserRole(teamAdminUserId, adminRoleId, teamId, null);
        insertUserRole(deputyAdminUserId, deputyAdminRoleId, teamId, null);

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
     * chat_channels へ最小限のチャンネル行を直接 INSERT する。
     * NOT NULL 全列を明示する（Builder.Default 由来の値も DB INSERT では明示しないと NOT NULL 違反になる）。
     */
    private Long insertChannel(String channelType, Long teamIdParam, Long orgIdParam, String name) {
        return insertChannel(channelType, teamIdParam, orgIdParam, name, false, false);
    }

    private Long insertChannel(String channelType, Long teamIdParam, Long orgIdParam, String name,
                               boolean isPrivate, boolean isInquiryChannel) {
        em.createNativeQuery(
                "INSERT INTO chat_channels ("
                        + "channel_type, team_id, organization_id, name, "
                        + "is_private, is_archived, is_inquiry_channel, active_thread_count, version, "
                        + "created_at, updated_at) "
                        + "VALUES (:ctype, :tid, :oid, :name, "
                        + ":priv, 0, :inq, 0, 0, "
                        + "NOW(), NOW())")
                .setParameter("ctype", channelType)
                .setParameter("tid", teamIdParam)
                .setParameter("oid", orgIdParam)
                .setParameter("name", name)
                .setParameter("priv", isPrivate ? 1 : 0)
                .setParameter("inq", isInquiryChannel ? 1 : 0)
                .executeUpdate();
        return ((Number) em.createNativeQuery(
                "SELECT id FROM chat_channels WHERE name = :name")
                .setParameter("name", name)
                .getSingleResult()).longValue();
    }

    /**
     * chat_messages へ最小限のメッセージ行を直接 INSERT する。
     */
    private Long insertMessage(Long channelId, Long senderId, String body) {
        em.createNativeQuery(
                "INSERT INTO chat_messages ("
                        + "channel_id, sender_id, posted_as_subject_type, body, "
                        + "is_edited, is_system, depth, reply_count, reaction_count, is_pinned, "
                        + "created_at, updated_at) "
                        + "VALUES (:cid, :sid, 'USER', :body, "
                        + "0, 0, 0, 0, 0, 0, "
                        + "NOW(), NOW())")
                .setParameter("cid", channelId)
                .setParameter("sid", senderId)
                .setParameter("body", body)
                .executeUpdate();
        return ((Number) em.createNativeQuery(
                "SELECT id FROM chat_messages WHERE body = :body")
                .setParameter("body", body)
                .getSingleResult()).longValue();
    }

    // =========================================================================
    // シナリオ
    // =========================================================================

    @Test
    @DisplayName("TEAM チャンネルのメッセージは所属メンバーのみ閲覧可（SCOPE_AFFILIATED 固定）")
    void team_channel_message_visible_to_member_only() {
        Long channelId = insertChannel("TEAM_PUBLIC", teamId, null, "team-ch");
        Long msgId = insertMessage(channelId, memberUserId, "team-msg-body");
        em.flush();
        em.clear();

        assertThat(checker.canView(ReferenceType.CHAT_MESSAGE, msgId, null)).isFalse();
        assertThat(checker.canView(ReferenceType.CHAT_MESSAGE, msgId, nonMemberUserId)).isFalse();
        assertThat(checker.canView(ReferenceType.CHAT_MESSAGE, msgId, memberUserId)).isTrue();
    }

    @Test
    @DisplayName("TEAM チャンネルのメッセージは SystemAdmin にも閲覧可（§15 D-13）")
    void team_channel_message_visible_to_system_admin() {
        Long channelId = insertChannel("TEAM_PUBLIC", teamId, null, "team-ch-sysadmin");
        Long msgId = insertMessage(channelId, memberUserId, "team-msg-sysadmin");
        em.flush();
        em.clear();

        assertThat(checker.canView(ReferenceType.CHAT_MESSAGE, msgId, sysAdminUserId)).isTrue();
    }

    @Test
    @DisplayName("ORGANIZATION チャンネルのメッセージも SCOPE_AFFILIATED 固定で評価される")
    void organization_channel_message_visible_to_member_only() {
        insertUserRole(memberUserId, memberRoleId, null, orgId); // org 直属メンバーシップ付与
        Long channelId = insertChannel("ORG_PUBLIC", null, orgId, "org-ch");
        Long msgId = insertMessage(channelId, memberUserId, "org-msg-body");
        em.flush();
        em.clear();

        assertThat(checker.canView(ReferenceType.CHAT_MESSAGE, msgId, null)).isFalse();
        assertThat(checker.canView(ReferenceType.CHAT_MESSAGE, msgId, nonMemberUserId)).isFalse();
        assertThat(checker.canView(ReferenceType.CHAT_MESSAGE, msgId, memberUserId)).isTrue();
    }

    @Test
    @DisplayName("DM チャンネル（team/org スコープ無し）のメッセージは非 SystemAdmin には fail-closed で不可視")
    void dm_channel_message_scope_null_fail_closed() {
        Long channelId = insertChannel("DM", null, null, "dm-ch");
        Long msgId = insertMessage(channelId, memberUserId, "dm-msg-body");
        em.flush();
        em.clear();

        assertThat(checker.canView(ReferenceType.CHAT_MESSAGE, msgId, memberUserId)).isFalse();
        assertThat(checker.canView(ReferenceType.CHAT_MESSAGE, msgId, nonMemberUserId)).isFalse();
        // SystemAdmin 高速パスは status 通過後に可視
        assertThat(checker.canView(ReferenceType.CHAT_MESSAGE, msgId, sysAdminUserId)).isTrue();
    }

    @Test
    @DisplayName("不存在 ID は誰に対しても false")
    void unknown_id_false() {
        assertThat(checker.canView(ReferenceType.CHAT_MESSAGE, 999_999L, memberUserId)).isFalse();
        assertThat(checker.canView(ReferenceType.CHAT_MESSAGE, 999_999L, sysAdminUserId)).isFalse();
    }

    @Test
    @DisplayName("論理削除済メッセージ (deleted_at != NULL) は誰にも不可視")
    void soft_deleted_message_invisible_to_all() {
        Long channelId = insertChannel("TEAM_PUBLIC", teamId, null, "team-ch-del");
        Long msgId = insertMessage(channelId, memberUserId, "team-msg-del");
        em.createNativeQuery("UPDATE chat_messages SET deleted_at = NOW() WHERE id = :id")
                .setParameter("id", msgId)
                .executeUpdate();
        em.flush();
        em.clear();

        assertThat(checker.canView(ReferenceType.CHAT_MESSAGE, msgId, memberUserId)).isFalse();
        assertThat(checker.canView(ReferenceType.CHAT_MESSAGE, msgId, sysAdminUserId)).isFalse();
    }

    @Test
    @DisplayName("論理削除済チャンネルのメッセージは fail-closed で不可視（結合先チャンネルが除外される）")
    void soft_deleted_channel_message_invisible() {
        Long channelId = insertChannel("TEAM_PUBLIC", teamId, null, "team-ch-chdel");
        Long msgId = insertMessage(channelId, memberUserId, "team-msg-chdel");
        em.createNativeQuery("UPDATE chat_channels SET deleted_at = NOW() WHERE id = :id")
                .setParameter("id", channelId)
                .executeUpdate();
        em.flush();
        em.clear();

        assertThat(checker.canView(ReferenceType.CHAT_MESSAGE, msgId, memberUserId)).isFalse();
        assertThat(checker.canView(ReferenceType.CHAT_MESSAGE, msgId, sysAdminUserId)).isFalse();
    }

    // =========================================================================
    // 粒度是正（検分指摘）: PRIVATE チャンネル / 問い合わせチャンネル
    // =========================================================================

    @Test
    @DisplayName("TEAM_PRIVATE チャンネル（is_private=1）はチーム所属だがチャンネル非メンバーの一般メンバーに不可視（fail-closed）")
    void team_private_channel_denied_to_scope_member_not_channel_member() {
        Long channelId = insertChannel("TEAM_PRIVATE", teamId, null, "team-private-ch", true, false);
        Long msgId = insertMessage(channelId, teamAdminUserId, "private-msg-body");
        em.flush();
        em.clear();

        // memberUserId はチーム所属だがチャンネルメンバーではない → deny
        assertThat(checker.canView(ReferenceType.CHAT_MESSAGE, msgId, memberUserId)).isFalse();
        assertThat(checker.canView(ReferenceType.CHAT_MESSAGE, msgId, nonMemberUserId)).isFalse();
        // SystemAdmin 高速パスは可視
        assertThat(checker.canView(ReferenceType.CHAT_MESSAGE, msgId, sysAdminUserId)).isTrue();
    }

    @Test
    @DisplayName("問い合わせチャンネル（is_inquiry_channel=1）は一般メンバーに不可視")
    void inquiry_channel_denied_to_regular_member() {
        Long channelId = insertChannel("TEAM_PUBLIC", teamId, null, "inquiry-ch-member", false, true);
        Long msgId = insertMessage(channelId, memberUserId, "inquiry-msg-member");
        em.flush();
        em.clear();

        assertThat(checker.canView(ReferenceType.CHAT_MESSAGE, msgId, memberUserId)).isFalse();
        assertThat(checker.canView(ReferenceType.CHAT_MESSAGE, msgId, nonMemberUserId)).isFalse();
        assertThat(checker.canView(ReferenceType.CHAT_MESSAGE, msgId, null)).isFalse();
    }

    @Test
    @DisplayName("問い合わせチャンネルはチーム ADMIN / DEPUTY_ADMIN に可視（通知受信者・回帰ガード）")
    void inquiry_channel_allowed_to_team_admin_and_deputy() {
        Long channelId = insertChannel("TEAM_PUBLIC", teamId, null, "inquiry-ch-admin", false, true);
        Long msgId = insertMessage(channelId, memberUserId, "inquiry-msg-admin");
        em.flush();
        em.clear();

        // 今回根治した「管理者への問い合わせ通知作成」の canView 前提（回帰ガード）
        assertThat(checker.canView(ReferenceType.CHAT_MESSAGE, msgId, teamAdminUserId)).isTrue();
        // 受信者集合は ADMIN + DEPUTY_ADMIN（InquiryChatEventListener と完全一致）
        assertThat(checker.canView(ReferenceType.CHAT_MESSAGE, msgId, deputyAdminUserId)).isTrue();
    }

    @Test
    @DisplayName("filterAccessible は所属メンバー視点で正しくフィルタ")
    void filterAccessible_mixed_for_member() {
        Long teamCh = insertChannel("TEAM_PUBLIC", teamId, null, "flt-team-ch");
        Long dmCh = insertChannel("DM", null, null, "flt-dm-ch");
        Long m1 = insertMessage(teamCh, memberUserId, "flt-1");
        Long m2 = insertMessage(teamCh, memberUserId, "flt-2");
        Long m3 = insertMessage(dmCh, memberUserId, "flt-3");
        em.flush();
        em.clear();

        Set<Long> nonMember = checker.filterAccessible(
                ReferenceType.CHAT_MESSAGE, List.of(m1, m2, m3), nonMemberUserId);
        assertThat(nonMember).isEmpty();

        Set<Long> member = checker.filterAccessible(
                ReferenceType.CHAT_MESSAGE, List.of(m1, m2, m3), memberUserId);
        assertThat(member).containsExactlyInAnyOrder(m1, m2); // m3 は DM（scope 無し）→ fail-closed

        Set<Long> sysAdmin = checker.filterAccessible(
                ReferenceType.CHAT_MESSAGE, List.of(m1, m2, m3), sysAdminUserId);
        assertThat(sysAdmin).containsExactlyInAnyOrder(m1, m2, m3);
    }

    private void insertRoleIfAbsent(String name, String displayName, int priority, boolean isSystem) {
        // 冪等化: roles はグローバル参照テーブルのため、既存なら再利用し二重INSERTしない
        // （同一 name の重複INSERTは roles の UNIQUE 制約違反になる。INSERT IGNORE は
        // 重複キー以外にもデータ切り詰め・NOT NULL違反等の異常を警告に格下げして黙って
        // 通してしまうため使用禁止。CI shard 再編成で同居テストが変わり得るため
        // 事前に SELECT で存在確認する）。
        Number existingRoleCount = (Number) em.createNativeQuery("SELECT COUNT(*) FROM roles WHERE name = :name")
                .setParameter("name", name)
                .getSingleResult();
        if (existingRoleCount.longValue() > 0) {
            return;
        }
        em.createNativeQuery(
                        "INSERT INTO roles (name, display_name, priority, is_system, created_at, updated_at) "
                                + "VALUES (:name, :dn, :priority, :sys, NOW(), NOW())")
                .setParameter("name", name)
                .setParameter("dn", displayName)
                .setParameter("priority", priority)
                .setParameter("sys", isSystem ? 1 : 0)
                .executeUpdate();
    }

}
