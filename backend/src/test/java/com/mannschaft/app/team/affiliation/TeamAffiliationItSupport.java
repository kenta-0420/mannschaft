package com.mannschaft.app.team.affiliation;

import com.mannschaft.app.common.visibility.RolePriority;
import com.mannschaft.app.membership.domain.RoleKind;
import com.mannschaft.app.membership.domain.ScopeType;
import com.mannschaft.app.support.test.AbstractMySqlIntegrationTest;
import com.mannschaft.app.support.test.MembershipTestHelper;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Collectors;

/**
 * F01.2.1 部隊 2-B1（チーム側の加盟申請）の統合テストが共有するフィクスチャ。
 *
 * <p>実 MySQL（Testcontainers）の上に、設計書 §16 の人物記号どおりの権限構成を作る。</p>
 * <ul>
 *   <li>TA＝チーム ADMIN、TD＝チーム DEPUTY_ADMIN（付与なし）、TM＝チーム MEMBER（付与なし）</li>
 *   <li>TG＝権限グループで {@code MANAGE_ORG_AFFILIATION} を付与されたチーム MEMBER</li>
 *   <li>XA＝組織 ADMIN</li>
 * </ul>
 *
 * <p>IT のスキーマは Flyway ではなく Entity から Hibernate が生成するため、権限行
 * （{@code permissions} と ADMIN の既定付与 {@code role_permissions}）は Flyway（V231）と同じ内容を冪等に seed する。
 * Flyway が実際にこの行を入れることは {@code OrgAffiliationPermissionFlywayIT}（AC-P01）が検証する。</p>
 *
 * <p>行は native SQL で作る（加盟の書き込み API を、検証対象の API 以外のフィクスチャ作りに使わない）。
 * {@code @Transactional} のテストでは自動でロールバックされる。コミットされるテスト
 * （並行 IT）は {@link #deleteCommittedFixtures(JdbcTemplate)} で後片付けする。</p>
 */
abstract class TeamAffiliationItSupport extends AbstractMySqlIntegrationTest {

    protected static final String MANAGE_ORG_AFFILIATION = "MANAGE_ORG_AFFILIATION";

    /** 他の IT のユーザー ID と重ならない帯。 */
    private static final AtomicLong USER_SEQ = new AtomicLong(940_210_000L);
    private static final AtomicLong SLUG_SEQ = new AtomicLong(0);

    @PersistenceContext
    protected EntityManager em;

    private final List<Long> createdUserIds = new ArrayList<>();
    private final List<Long> createdTeamIds = new ArrayList<>();
    private final List<Long> createdOrgIds = new ArrayList<>();

    /** 作成したチーム（ID と slug）。 */
    protected record TeamFx(long id, String slug, String name) {
    }

    /** 作成した組織（ID と slug）。 */
    protected record OrgFx(long id, String slug, String name) {
    }

    // =====================================================================
    // 人・チーム・組織
    // =====================================================================

    protected long newUser() {
        long id = USER_SEQ.incrementAndGet();
        MembershipTestHelper.insertActiveUser(em, id);
        createdUserIds.add(id);
        return id;
    }

    protected TeamFx newTeam() {
        String slug = "af-t-" + uniqueSuffix();
        String name = "加盟申請チーム" + slug;
        em.createNativeQuery(
                        "INSERT INTO teams (name, visibility, supporter_enabled, version, member_count, slug, "
                                + "lifecycle_status, created_at, updated_at) "
                                + "VALUES (:name, 'PUBLIC', 1, 0, 0, :slug, 'ACTIVE', NOW(), NOW())")
                .setParameter("name", name)
                .setParameter("slug", slug)
                .executeUpdate();
        long id = ((Number) em.createNativeQuery("SELECT id FROM teams WHERE slug = :slug")
                .setParameter("slug", slug).getSingleResult()).longValue();
        createdTeamIds.add(id);
        return new TeamFx(id, slug, name);
    }

    /** 申請を受け付ける公開組織（グループ機能 off）。 */
    protected OrgFx newOrg() {
        return newOrg(true, false, "OFF", "PUBLIC");
    }

    /**
     * 組織を作る。
     *
     * @param applicationEnabled チームからの加盟申請を受け付けるか
     * @param groupsEnabled      チームグループ機能が有効か
     * @param groupMode          申請時のグループ選択（OFF / OPTIONAL / REQUIRED の保存値）
     * @param visibility         PUBLIC / PRIVATE
     */
    protected OrgFx newOrg(boolean applicationEnabled, boolean groupsEnabled, String groupMode, String visibility) {
        String slug = "af-o-" + uniqueSuffix();
        String name = "加盟申請組織" + slug;
        em.createNativeQuery(
                        "INSERT INTO organizations (name, org_type, visibility, hierarchy_visibility, "
                                + "supporter_enabled, version, slug, lifecycle_status, "
                                + "team_application_enabled, team_groups_enabled, team_application_group_mode, "
                                + "created_at, updated_at) "
                                + "VALUES (:name, 'OTHER', :visibility, 'NONE', 1, 0, :slug, 'ACTIVE', "
                                + ":appEnabled, :groupsEnabled, :groupMode, NOW(), NOW())")
                .setParameter("name", name)
                .setParameter("visibility", visibility)
                .setParameter("slug", slug)
                .setParameter("appEnabled", applicationEnabled ? 1 : 0)
                .setParameter("groupsEnabled", groupsEnabled ? 1 : 0)
                .setParameter("groupMode", groupMode)
                .executeUpdate();
        long id = ((Number) em.createNativeQuery("SELECT id FROM organizations WHERE slug = :slug")
                .setParameter("slug", slug).getSingleResult()).longValue();
        createdOrgIds.add(id);
        return new OrgFx(id, slug, name);
    }

    protected void archiveOrganization(long orgId) {
        em.createNativeQuery("UPDATE organizations SET archived_at = NOW() WHERE id = :id")
                .setParameter("id", orgId).executeUpdate();
    }

    /** 組織のチームグループを作る（既存の org_team_groups の DDL どおり。削除済みは deleted_at を立てる）。 */
    protected UUID newGroup(long orgId, String name, boolean deleted) {
        UUID id = com.mannschaft.app.common.UuidV7.generate();
        em.createNativeQuery(
                        "INSERT INTO org_team_groups (id, organization_id, name, sort_order, created_at, updated_at, "
                                + "deleted_at) VALUES (:id, :orgId, :name, 0, UTC_TIMESTAMP(), UTC_TIMESTAMP(), "
                                + (deleted ? "UTC_TIMESTAMP()" : "NULL") + ")")
                .setParameter("id", id)
                .setParameter("orgId", orgId)
                .setParameter("name", name)
                .executeUpdate();
        return id;
    }

    // =====================================================================
    // ロールと権限
    // =====================================================================

    /** ADMIN / DEPUTY_ADMIN / MEMBER のロール行と、MANAGE_ORG_AFFILIATION の権限行・ADMIN の既定付与を用意する（冪等）。 */
    protected void seedAffiliationPermission() {
        for (String role : List.of("ADMIN", "DEPUTY_ADMIN", "MEMBER")) {
            em.createNativeQuery(
                            "INSERT INTO roles (name, display_name, priority, is_system, created_at, updated_at) "
                                    + "SELECT :name, :name, :priority, 0, NOW(), NOW() FROM DUAL "
                                    + "WHERE NOT EXISTS (SELECT 1 FROM roles WHERE name = :name)")
                    .setParameter("name", role)
                    .setParameter("priority", RolePriority.priority(role))
                    .executeUpdate();
        }
        em.createNativeQuery(
                        "INSERT INTO permissions (name, display_name, scope, created_at, updated_at) "
                                + "SELECT :name, :name, 'TEAM', NOW(), NOW() FROM DUAL "
                                + "WHERE NOT EXISTS (SELECT 1 FROM permissions WHERE name = :name)")
                .setParameter("name", MANAGE_ORG_AFFILIATION)
                .executeUpdate();
        em.createNativeQuery(
                        "INSERT INTO role_permissions (role_id, permission_id, is_default, created_at) "
                                + "SELECT r.id, p.id, 1, NOW() FROM roles r, permissions p "
                                + "WHERE r.name = 'ADMIN' AND p.name = :perm "
                                + "AND NOT EXISTS (SELECT 1 FROM role_permissions rp "
                                + "WHERE rp.role_id = r.id AND rp.permission_id = p.id)")
                .setParameter("perm", MANAGE_ORG_AFFILIATION)
                .executeUpdate();
    }

    protected void makeTeamAdmin(long userId, long teamId) {
        MembershipTestHelper.insertMembership(em, userId, ScopeType.TEAM, teamId, RoleKind.MEMBER);
        MembershipTestHelper.insertUserRole(em, userId, "ADMIN", teamId, null);
    }

    protected void makeTeamDeputy(long userId, long teamId) {
        MembershipTestHelper.insertMembership(em, userId, ScopeType.TEAM, teamId, RoleKind.MEMBER);
        MembershipTestHelper.insertUserRole(em, userId, "DEPUTY_ADMIN", teamId, null);
    }

    protected void makeTeamMember(long userId, long teamId) {
        MembershipTestHelper.insertMembership(em, userId, ScopeType.TEAM, teamId, RoleKind.MEMBER);
    }

    protected void makeOrgAdmin(long userId, long orgId) {
        MembershipTestHelper.insertMembership(em, userId, ScopeType.ORGANIZATION, orgId, RoleKind.MEMBER);
        MembershipTestHelper.insertUserRole(em, userId, "ADMIN", null, orgId);
    }

    /**
     * 権限グループ経由で MANAGE_ORG_AFFILIATION を付与する（AC-P03・P04）。
     *
     * @param targetRole DEPUTY_ADMIN または MEMBER（付与対象のロールと権限グループの target_role は一致させる）
     */
    protected void grantAffiliationByPermissionGroup(long userId, long teamId, String targetRole) {
        String groupName = "加盟操作-" + uniqueSuffix();
        em.createNativeQuery(
                        "INSERT INTO permission_groups (team_id, organization_id, target_role, name, created_by, "
                                + "deleted_at, created_at, updated_at) "
                                + "VALUES (:teamId, NULL, :targetRole, :name, NULL, NULL, NOW(), NOW())")
                .setParameter("teamId", teamId)
                .setParameter("targetRole", targetRole)
                .setParameter("name", groupName)
                .executeUpdate();
        long groupId = ((Number) em.createNativeQuery("SELECT id FROM permission_groups WHERE name = :name")
                .setParameter("name", groupName).getSingleResult()).longValue();
        em.createNativeQuery(
                        "INSERT INTO permission_group_permissions (group_id, permission_id, created_at) "
                                + "SELECT :groupId, p.id, NOW() FROM permissions p WHERE p.name = :perm")
                .setParameter("groupId", groupId)
                .setParameter("perm", MANAGE_ORG_AFFILIATION)
                .executeUpdate();
        em.createNativeQuery(
                        "INSERT INTO user_permission_groups (user_id, group_id, assigned_by, created_at) "
                                + "VALUES (:userId, :groupId, NULL, NOW())")
                .setParameter("userId", userId)
                .setParameter("groupId", groupId)
                .executeUpdate();
    }

    // =====================================================================
    // 加盟の行（検証対象の API を使わずに前提状態を作る）
    // =====================================================================

    /** team_org_memberships の行を native INSERT する。返り値は membershipId。 */
    protected long insertMembershipRow(long teamId, long orgId, String status, String direction,
                                       UUID groupId, LocalDateTime invitedAt) {
        // group_id が NULL のときはリテラルで書く（型のない null のバインドを避ける）
        var insert = em.createNativeQuery(
                        "INSERT INTO team_org_memberships (team_id, organization_id, status, direction, group_id, "
                                + "invited_at, created_at, updated_at) "
                                + "VALUES (:teamId, :orgId, :status, :direction, "
                                + (groupId == null ? "NULL" : ":groupId")
                                + ", :invitedAt, :invitedAt, UTC_TIMESTAMP())")
                .setParameter("teamId", teamId)
                .setParameter("orgId", orgId)
                .setParameter("status", status)
                .setParameter("direction", direction)
                .setParameter("invitedAt", invitedAt);
        if (groupId != null) {
            insert.setParameter("groupId", groupId);
        }
        insert.executeUpdate();
        return ((Number) em.createNativeQuery(
                        "SELECT id FROM team_org_memberships WHERE team_id = :teamId AND organization_id = :orgId")
                .setParameter("teamId", teamId)
                .setParameter("orgId", orgId)
                .getSingleResult()).longValue();
    }

    protected long countMemberships(long teamId, long orgId) {
        return ((Number) em.createNativeQuery(
                        "SELECT COUNT(*) FROM team_org_memberships WHERE team_id = :teamId AND organization_id = :orgId")
                .setParameter("teamId", teamId)
                .setParameter("orgId", orgId)
                .getSingleResult()).longValue();
    }

    // =====================================================================
    // 後片付け（コミットされるテスト用）
    // =====================================================================

    /** このインスタンスが作ったユーザー・チーム・組織と、それに紐づく行をすべて物理削除する。 */
    protected void deleteCommittedFixtures(JdbcTemplate jdbc) {
        String users = idList(createdUserIds);
        String teams = idList(createdTeamIds);
        String orgs = idList(createdOrgIds);
        if (!createdOrgIds.isEmpty()) {
            // 通知 outbox（docs/architecture/notification_outbox.md）。加盟の通知はテナントの組織 ID を写しに持つ
            jdbc.update("DELETE FROM team_notification_outbox WHERE organization_id IN (" + orgs + ")");
            jdbc.update("DELETE FROM notification_fanout_job_messages WHERE job_id IN "
                    + "(SELECT id FROM notification_fanout_jobs WHERE organization_id IN (" + orgs + "))");
            jdbc.update("DELETE FROM notification_fanout_jobs WHERE organization_id IN (" + orgs + ")");
            jdbc.update("DELETE FROM org_team_groups WHERE organization_id IN (" + orgs + ")");
        }
        if (!createdUserIds.isEmpty()) {
            jdbc.update("DELETE FROM notifications WHERE user_id IN (" + users + ")");
            jdbc.update("DELETE FROM user_permission_groups WHERE user_id IN (" + users + ")");
            jdbc.update("DELETE FROM user_roles WHERE user_id IN (" + users + ")");
            jdbc.update("DELETE FROM memberships WHERE user_id IN (" + users + ")");
        }
        if (!createdTeamIds.isEmpty()) {
            jdbc.update("DELETE FROM permission_group_permissions WHERE group_id IN "
                    + "(SELECT id FROM permission_groups WHERE team_id IN (" + teams + "))");
            jdbc.update("DELETE FROM permission_groups WHERE team_id IN (" + teams + ")");
            jdbc.update("DELETE FROM audit_logs WHERE team_id IN (" + teams + ")");
            jdbc.update("DELETE FROM team_org_affiliation_restrictions WHERE team_id IN (" + teams + ")");
            jdbc.update("DELETE FROM team_org_memberships WHERE team_id IN (" + teams + ")");
            jdbc.update("DELETE FROM teams WHERE id IN (" + teams + ")");
        }
        if (!createdOrgIds.isEmpty()) {
            jdbc.update("DELETE FROM team_org_affiliation_restrictions WHERE organization_id IN (" + orgs + ")");
            jdbc.update("DELETE FROM team_org_memberships WHERE organization_id IN (" + orgs + ")");
            jdbc.update("DELETE FROM organizations WHERE id IN (" + orgs + ")");
        }
        if (!createdUserIds.isEmpty()) {
            jdbc.update("DELETE FROM users WHERE id IN (" + users + ")");
        }
    }

    private static String idList(List<Long> ids) {
        return ids.isEmpty() ? "NULL" : ids.stream().map(String::valueOf).collect(Collectors.joining(","));
    }

    private static String uniqueSuffix() {
        return Long.toString(System.nanoTime() % 1_000_000_000L, 36) + SLUG_SEQ.incrementAndGet();
    }
}
