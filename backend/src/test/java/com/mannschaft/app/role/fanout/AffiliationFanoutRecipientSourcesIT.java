package com.mannschaft.app.role.fanout;

import com.mannschaft.app.notification.NotificationPriority;
import com.mannschaft.app.notification.fanout.FanoutEnqueueCommand;
import com.mannschaft.app.notification.fanout.FanoutMessageKind;
import com.mannschaft.app.notification.fanout.FanoutPageRequest;
import com.mannschaft.app.notification.fanout.FanoutRecipient;
import com.mannschaft.app.notification.fanout.FanoutRecipientSourceRegistry;
import com.mannschaft.app.notification.fanout.NotificationFanoutJobRepository;
import com.mannschaft.app.notification.fanout.NotificationFanoutJobService;
import com.mannschaft.app.notification.fanout.NotificationFanoutJobStatus;
import com.mannschaft.app.notification.fanout.NotificationFanoutWorker;
import com.mannschaft.app.support.test.AbstractMySqlIntegrationTest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * F01.2.1 6-D 試練: 加盟の通知の受信者ソース（AC-G117 の受信者解決・§6.7）。
 *
 * <h2>AC ↔ テスト対応</h2>
 * <ul>
 *   <li>{@code ORGANIZATION_ADMINS} は組織 ADMIN（在籍中・ACTIVE）だけを返す
 *       → {@link #組織ADMINSは組織のADMINだけを返す()}</li>
 *   <li>{@code TEAM_AFFILIATION_OPS} はチーム ADMIN と権限を付与された DEPUTY・MEMBER を返し、
 *       付与されていない人・他チームの人は返さない → {@link #チーム加盟操作者はADMINと権限を付与された人だけを返す()}</li>
 *   <li>キーセットページング（user_id 昇順・カーソルの後ろから）
 *       → {@link #チーム加盟操作者はuser_id昇順でカーソルの後ろから返す()}</li>
 *   <li>レジストリへの登録と {@code scope_type} の長さ（VARCHAR(20)）→ {@link #二つの受信者ソースがレジストリに登録され列長に収まる()}</li>
 *   <li>G117 の受信者: 新版で enqueue したジョブを Worker が配信すると組織 ADMIN にだけ action_url 付きで届く
 *       → {@link #組織ADMIN宛てのジョブはWorkerが組織ADMINにだけaction_url付きで配信する()}</li>
 * </ul>
 *
 * <p>権限 {@code MANAGE_ORG_AFFILIATION} の付与は、5-A（Flyway の権限追加）を待たず、フィクスチャで
 * {@code permissions}・{@code permission_groups}・{@code permission_group_permissions}・{@code user_permission_groups}
 * の行を直接 INSERT して作る。</p>
 */
@DisplayName("F01.2.1 6-D 加盟の通知の受信者ソース 試練（AC-G117 受信者解決）")
@EnabledIf("com.mannschaft.app.support.test.AbstractMySqlIntegrationTest#isDockerAvailable")
class AffiliationFanoutRecipientSourcesIT extends AbstractMySqlIntegrationTest {

    private static final String MANAGE_ORG_AFFILIATION = "MANAGE_ORG_AFFILIATION";
    private static final String OTHER_PERMISSION = "F0121_IT_OTHER_PERMISSION";

    @Autowired
    private OrganizationAdminsFanoutRecipientSource organizationAdminsSource;
    @Autowired
    private TeamAffiliationOperatorsFanoutRecipientSource teamAffiliationOpsSource;
    @Autowired
    private FanoutRecipientSourceRegistry registry;
    @Autowired
    private NotificationFanoutJobService jobService;
    @Autowired
    private NotificationFanoutJobRepository jobRepository;
    @Autowired
    private NotificationFanoutWorker worker;
    @Autowired
    private PlatformTransactionManager transactionManager;
    @Autowired
    private JdbcTemplate jdbc;

    // =====================================================================
    // ORGANIZATION_ADMINS
    // =====================================================================

    @Test
    @DisplayName("ORGANIZATION_ADMINS: 組織の ADMIN だけを返す（DEPUTY・MEMBER・退会者・他組織の ADMIN は返さない）")
    void 組織ADMINSは組織のADMINだけを返す() {
        OrgFixture f = seedOrganization();

        List<Long> ids = userIds(organizationAdminsSource.nextPage(page(f.orgId, 0L, 100)));

        assertThat(ids).as("組織 ADMIN 2名だけが user_id 昇順で返る")
                .containsExactly(f.admin1, f.admin2);
    }

    // =====================================================================
    // TEAM_AFFILIATION_OPS
    // =====================================================================

    @Test
    @DisplayName("TEAM_AFFILIATION_OPS: チーム ADMIN と MANAGE_ORG_AFFILIATION を付与された DEPUTY・MEMBER だけを返す")
    void チーム加盟操作者はADMINと権限を付与された人だけを返す() {
        TeamFixture f = seedTeam();

        List<Long> ids = userIds(teamAffiliationOpsSource.nextPage(page(f.teamId, 0L, 100)));

        assertThat(ids).as("チーム ADMIN・権限付与済み DEPUTY・権限付与済み MEMBER の3名だけ")
                .containsExactly(f.admin, f.grantedDeputy, f.grantedMember);
        assertThat(ids).as("付与されていない DEPUTY・MEMBER は返さない")
                .doesNotContain(f.plainDeputy, f.plainMember);
        assertThat(ids).as("別の権限だけを付与された MEMBER は返さない").doesNotContain(f.otherPermissionMember);
        assertThat(ids).as("他チームの ADMIN・他チームで権限を付与された人は返さない")
                .doesNotContain(f.otherTeamAdmin, f.otherTeamGrantedMember);
        assertThat(ids).as("在籍していない（ロールの無い）人は、権限グループが残っていても返さない")
                .doesNotContain(f.leftButGrantedUser);
        assertThat(ids).as("退会済み（deleted_at あり）の ADMIN は返さない").doesNotContain(f.deletedAdmin);
        assertThat(ids).as("削除済みの権限グループによる付与は無効").doesNotContain(f.deletedGroupMember);
    }

    @Test
    @DisplayName("TEAM_AFFILIATION_OPS: user_id 昇順でカーソルの後ろから limit 件ずつ返す")
    void チーム加盟操作者はuser_id昇順でカーソルの後ろから返す() {
        TeamFixture f = seedTeam();

        List<Long> first = userIds(teamAffiliationOpsSource.nextPage(page(f.teamId, 0L, 2)));
        assertThat(first).containsExactly(f.admin, f.grantedDeputy);

        List<Long> second = userIds(teamAffiliationOpsSource.nextPage(page(f.teamId, first.get(1), 2)));
        assertThat(second).containsExactly(f.grantedMember);

        List<Long> third = userIds(teamAffiliationOpsSource.nextPage(page(f.teamId, f.grantedMember, 2)));
        assertThat(third).isEmpty();
    }

    // =====================================================================
    // 配線
    // =====================================================================

    @Test
    @DisplayName("2つの受信者ソースがレジストリに登録され、scope_type は VARCHAR(20) に収まる")
    void 二つの受信者ソースがレジストリに登録され列長に収まる() {
        assertThat(registry.resolve("ORGANIZATION_ADMINS")).containsSame(organizationAdminsSource);
        assertThat(registry.resolve("TEAM_AFFILIATION_OPS")).containsSame(teamAffiliationOpsSource);
        assertThat(OrganizationAdminsFanoutRecipientSource.SCOPE_TYPE).hasSizeLessThanOrEqualTo(20);
        assertThat(TeamAffiliationOperatorsFanoutRecipientSource.SCOPE_TYPE).hasSizeLessThanOrEqualTo(20);
    }

    @Test
    @DisplayName("G117: 新版で enqueue した ORGANIZATION_ADMINS のジョブを Worker が配信すると、組織 ADMIN にだけ action_url 付きで届く")
    void 組織ADMIN宛てのジョブはWorkerが組織ADMINにだけaction_url付きで配信する() {
        OrgFixture f = seedOrganization();
        String notificationType = "F0121_IT_G117_" + UUID.randomUUID().toString().substring(0, 8);
        String actionUrl = "/organizations/f0121-it/member-teams?view=applications";

        UUID jobId = new TransactionTemplate(transactionManager).execute(status ->
                jobService.enqueueInCurrentTransaction(new FanoutEnqueueCommand(
                        OrganizationAdminsFanoutRecipientSource.SCOPE_TYPE, String.valueOf(f.orgId),
                        notificationType, UUID.randomUUID(), f.orgId, NotificationPriority.NORMAL, null,
                        "TEAM_ORG_MEMBERSHIP", 1L, actionUrl, false,
                        FanoutMessageKind.SURVEY_PUBLISHED, List.of("G117"),
                        FanoutEnqueueCommand.ShardMode.FIXED_SINGLE)).jobId());

        worker.processOne(jobRepository.findById(jobId).orElseThrow());

        assertThat(jobRepository.findById(jobId).orElseThrow().getStatus())
                .isEqualTo(NotificationFanoutJobStatus.DONE);
        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT user_id, action_url FROM notifications WHERE notification_type = ? ORDER BY user_id",
                notificationType);
        assertThat(rows).extracting(r -> ((Number) r.get("user_id")).longValue())
                .as("組織 ADMIN 2名にだけ届く").containsExactly(f.admin1, f.admin2);
        assertThat(rows).extracting(r -> r.get("action_url")).containsOnly(actionUrl);
    }

    // =====================================================================
    // 本番の在籍の形（V60.010 以降: 一般 MEMBER は memberships にしか居ない・退会は left_at）
    // =====================================================================

    @Test
    @DisplayName("TEAM_AFFILIATION_OPS: user_roles 行が無く memberships だけの MEMBER に権限を付与しても返る（退会済みは返らない）")
    void チーム加盟操作者はmembershipsのみのMEMBERにも権限付与で返り退会済みは返らない() {
        long teamId = insertTeam();
        long permissionId = ensurePermission(MANAGE_ORG_AFFILIATION);
        ensureRoles();
        long admin = insertUser(false);
        long leftAdmin = insertUser(false);
        long membershipOnlyGranted = insertUser(false);
        long membershipOnlyPlain = insertUser(false);
        long leftGranted = insertUser(false);

        grantTeamRole(admin, teamId, "ADMIN");
        grantTeamRole(leftAdmin, teamId, "ADMIN");
        markMembershipLeft(leftAdmin, "TEAM", teamId);
        insertMembership(membershipOnlyGranted, "TEAM", teamId);
        insertMembership(membershipOnlyPlain, "TEAM", teamId);
        insertMembership(leftGranted, "TEAM", teamId);
        markMembershipLeft(leftGranted, "TEAM", teamId);

        long memberGroup = insertPermissionGroup(teamId, "MEMBER", false, permissionId);
        assignGroup(membershipOnlyGranted, memberGroup);
        assignGroup(leftGranted, memberGroup);

        List<Long> ids = userIds(teamAffiliationOpsSource.nextPage(page(teamId, 0L, 100)));

        assertThat(ids).as("ADMIN と、memberships のみで権限付与された MEMBER だけが返る")
                .containsExactly(admin, membershipOnlyGranted);
        assertThat(ids).as("退会済み（left_at あり）の ADMIN・権限付与済みメンバーは user_roles や割当が残っても返らない")
                .doesNotContain(leftAdmin, leftGranted);
        assertThat(ids).as("権限を付与されていない memberships のみの MEMBER は返らない")
                .doesNotContain(membershipOnlyPlain);
    }

    @Test
    @DisplayName("TEAM_AFFILIATION_OPS: 権限グループの target_role が実効ロールと一致しない割当を持つ人は返らない")
    void チーム加盟操作者はtarget_role不一致の割当では返らない() {
        long teamId = insertTeam();
        long permissionId = ensurePermission(MANAGE_ORG_AFFILIATION);
        ensureRoles();
        long matchedMember = insertUser(false);
        long mismatchedMember = insertUser(false);
        long matchedDeputy = insertUser(false);
        long mismatchedDeputy = insertUser(false);

        insertMembership(matchedMember, "TEAM", teamId);
        insertMembership(mismatchedMember, "TEAM", teamId);
        grantTeamRole(matchedDeputy, teamId, "DEPUTY_ADMIN");
        grantTeamRole(mismatchedDeputy, teamId, "DEPUTY_ADMIN");

        long memberGroup = insertPermissionGroup(teamId, "MEMBER", false, permissionId);
        long deputyGroup = insertPermissionGroup(teamId, "DEPUTY_ADMIN", false, permissionId);
        assignGroup(matchedMember, memberGroup);
        assignGroup(matchedDeputy, deputyGroup);
        // MEMBER なのに DEPUTY_ADMIN 向けグループ、DEPUTY_ADMIN なのに MEMBER 向けグループ（割当後にグループの対象を変えた状態）
        assignGroup(mismatchedMember, deputyGroup);
        assignGroup(mismatchedDeputy, memberGroup);

        List<Long> ids = userIds(teamAffiliationOpsSource.nextPage(page(teamId, 0L, 100)));

        assertThat(ids).as("実効ロールと target_role が一致する割当だけが有効").containsExactly(matchedMember, matchedDeputy);
        assertThat(ids).as("target_role 不一致の割当は無効扱い（resolveEffectivePermissions と同じ）")
                .doesNotContain(mismatchedMember, mismatchedDeputy);
    }

    @Test
    @DisplayName("ORGANIZATION_ADMINS: 退会済み（memberships.left_at あり）の ADMIN は user_roles が残っていても返らない")
    void 組織ADMINSは退会済みのADMINを返さない() {
        ensureRoles();
        long orgId = insertOrganization();
        long admin = insertUser(false);
        long leftAdmin = insertUser(false);
        grantOrgRole(admin, orgId, "ADMIN");
        grantOrgRole(leftAdmin, orgId, "ADMIN");
        markMembershipLeft(leftAdmin, "ORGANIZATION", orgId);

        List<Long> ids = userIds(organizationAdminsSource.nextPage(page(orgId, 0L, 100)));

        assertThat(ids).containsExactly(admin);
    }

    // =====================================================================
    // フィクスチャ
    // =====================================================================

    private record OrgFixture(long orgId, long admin1, long admin2) {
    }

    private record TeamFixture(long teamId, long admin, long grantedDeputy, long grantedMember,
                               long plainDeputy, long plainMember, long otherPermissionMember,
                               long otherTeamAdmin, long otherTeamGrantedMember, long leftButGrantedUser,
                               long deletedAdmin, long deletedGroupMember) {
    }

    /**
     * roles はグローバル参照テーブルで、IT のスキーマは Hibernate 生成のため初期行が無い。
     * 既存なら再利用し、無ければ入れる（同一 name の二重 INSERT は UNIQUE 違反になるため冪等化する）。
     */
    private void ensureRoles() {
        int[] priorities = {1, 2, 3};
        String[] names = {"ADMIN", "DEPUTY_ADMIN", "MEMBER"};
        for (int i = 0; i < names.length; i++) {
            Integer count = jdbc.queryForObject("SELECT COUNT(*) FROM roles WHERE name = ?", Integer.class, names[i]);
            if (count == null || count == 0) {
                jdbc.update("INSERT INTO roles (name, display_name, priority, is_system, created_at, updated_at) "
                        + "VALUES (?, ?, ?, 1, NOW(), NOW())", names[i], names[i], priorities[i]);
            }
        }
    }

    private OrgFixture seedOrganization() {
        ensureRoles();
        long orgId = insertOrganization();
        long otherOrgId = insertOrganization();
        long admin1 = insertUser(false);
        long deputy = insertUser(false);
        long member = insertUser(false);
        long admin2 = insertUser(false);
        long deletedAdmin = insertUser(true);
        long otherOrgAdmin = insertUser(false);
        grantOrgRole(admin1, orgId, "ADMIN");
        grantOrgRole(deputy, orgId, "DEPUTY_ADMIN");
        grantOrgRole(member, orgId, "MEMBER");
        grantOrgRole(admin2, orgId, "ADMIN");
        grantOrgRole(deletedAdmin, orgId, "ADMIN");
        grantOrgRole(otherOrgAdmin, otherOrgId, "ADMIN");
        return new OrgFixture(orgId, admin1, admin2);
    }

    private TeamFixture seedTeam() {
        ensureRoles();
        long teamId = insertTeam();
        long otherTeamId = insertTeam();
        long affiliationPermission = ensurePermission(MANAGE_ORG_AFFILIATION);
        long otherPermission = ensurePermission(OTHER_PERMISSION);

        long admin = insertUser(false);
        long grantedDeputy = insertUser(false);
        long grantedMember = insertUser(false);
        long plainDeputy = insertUser(false);
        long plainMember = insertUser(false);
        long otherPermissionMember = insertUser(false);
        long otherTeamAdmin = insertUser(false);
        long otherTeamGrantedMember = insertUser(false);
        long leftButGrantedUser = insertUser(false);
        long deletedAdmin = insertUser(true);
        long deletedGroupMember = insertUser(false);

        grantTeamRole(admin, teamId, "ADMIN");
        grantTeamRole(grantedDeputy, teamId, "DEPUTY_ADMIN");
        grantTeamRole(grantedMember, teamId, "MEMBER");
        grantTeamRole(plainDeputy, teamId, "DEPUTY_ADMIN");
        grantTeamRole(plainMember, teamId, "MEMBER");
        grantTeamRole(otherPermissionMember, teamId, "MEMBER");
        grantTeamRole(otherTeamAdmin, otherTeamId, "ADMIN");
        grantTeamRole(otherTeamGrantedMember, otherTeamId, "MEMBER");
        grantTeamRole(deletedAdmin, teamId, "ADMIN");
        grantTeamRole(deletedGroupMember, teamId, "MEMBER");
        // leftButGrantedUser はチームのロールを持たない（離脱済み）が、権限グループの割当だけ残っている。

        long deputyGroup = insertPermissionGroup(teamId, "DEPUTY_ADMIN", false, affiliationPermission);
        long memberGroup = insertPermissionGroup(teamId, "MEMBER", false, affiliationPermission);
        long otherPermissionGroup = insertPermissionGroup(teamId, "MEMBER", false, otherPermission);
        long otherTeamGroup = insertPermissionGroup(otherTeamId, "MEMBER", false, affiliationPermission);
        long deletedGroup = insertPermissionGroup(teamId, "MEMBER", true, affiliationPermission);

        assignGroup(grantedDeputy, deputyGroup);
        assignGroup(grantedMember, memberGroup);
        assignGroup(otherPermissionMember, otherPermissionGroup);
        assignGroup(otherTeamGrantedMember, otherTeamGroup);
        assignGroup(leftButGrantedUser, memberGroup);
        assignGroup(deletedGroupMember, deletedGroup);

        return new TeamFixture(teamId, admin, grantedDeputy, grantedMember, plainDeputy, plainMember,
                otherPermissionMember, otherTeamAdmin, otherTeamGrantedMember, leftButGrantedUser,
                deletedAdmin, deletedGroupMember);
    }

    private long insertUser(boolean deleted) {
        String email = "f0121-g117-" + UUID.randomUUID() + "@example.test";
        jdbc.update("INSERT INTO users (email, last_name, first_name, display_name, status, deleted_at, "
                        + "is_searchable, handle_searchable, contact_approval_required, "
                        + "online_visibility, dm_receive_from, encryption_key_version, "
                        + "locale, timezone, reporting_restricted, follow_list_visibility, "
                        + "care_notification_enabled, offline_only, created_at, updated_at) "
                        + "VALUES (?, 'G117', 'テスト', 'G117テスト', 'ACTIVE', " + (deleted ? "NOW()" : "NULL") + ", "
                        + "1, 1, 1, 'NOBODY', 'ANYONE', 1, 'ja', 'Asia/Tokyo', 0, 'PUBLIC', 1, 0, NOW(), NOW())",
                email);
        return jdbc.queryForObject("SELECT id FROM users WHERE email = ?", Long.class, email);
    }

    private long insertOrganization() {
        String name = "F0121-G117-org-" + UUID.randomUUID();
        jdbc.update("INSERT INTO organizations (name, org_type, visibility, hierarchy_visibility, "
                + "supporter_enabled, version, slug, created_at, updated_at) "
                + "VALUES (?, 'OTHER', 'PUBLIC', 'NONE', 1, 0, "
                + "CONCAT('g117o-', LEFT(REPLACE(UUID(),'-',''),10)), NOW(), NOW())", name);
        return jdbc.queryForObject("SELECT id FROM organizations WHERE name = ?", Long.class, name);
    }

    private long insertTeam() {
        String name = "F0121-G117-team-" + UUID.randomUUID();
        jdbc.update("INSERT INTO teams (name, visibility, supporter_enabled, version, member_count, slug, "
                + "created_at, updated_at) VALUES (?, 'PUBLIC', 1, 0, 0, "
                + "CONCAT('g117t-', LEFT(REPLACE(UUID(),'-',''),10)), NOW(), NOW())", name);
        return jdbc.queryForObject("SELECT id FROM teams WHERE name = ?", Long.class, name);
    }

    private void grantOrgRole(long userId, long orgId, String roleName) {
        jdbc.update("INSERT INTO user_roles (user_id, role_id, team_id, organization_id, created_at, updated_at) "
                + "VALUES (?, (SELECT id FROM roles WHERE name = ?), NULL, ?, NOW(), NOW())", userId, roleName, orgId);
        insertMembership(userId, "ORGANIZATION", orgId);
    }

    private void grantTeamRole(long userId, long teamId, String roleName) {
        jdbc.update("INSERT INTO user_roles (user_id, role_id, team_id, organization_id, created_at, updated_at) "
                + "VALUES (?, (SELECT id FROM roles WHERE name = ?), ?, NULL, NOW(), NOW())", userId, roleName, teamId);
        insertMembership(userId, "TEAM", teamId);
    }

    /** memberships 系統にも在籍行を置く（ロール判定が両系統を見るため・在籍の実態を揃える）。 */
    private void insertMembership(long userId, String scopeType, long scopeId) {
        jdbc.update("INSERT INTO memberships (user_id, scope_type, scope_id, role_kind, joined_at, created_at, updated_at) "
                + "VALUES (?, ?, ?, 'MEMBER', NOW(), NOW(), NOW())", userId, scopeType, scopeId);
    }

    /** 退会させる（memberships.left_at と leave_reason を同時に入れる。CHECK 制約 chk_memberships_left_reason）。 */
    private void markMembershipLeft(long userId, String scopeType, long scopeId) {
        jdbc.update("UPDATE memberships SET left_at = DATE_ADD(joined_at, INTERVAL 1 SECOND), leave_reason = 'SELF' "
                + "WHERE user_id = ? AND scope_type = ? AND scope_id = ?", userId, scopeType, scopeId);
    }

    /** 権限行を用意する（5-A の Flyway が入った後も重複しないよう INSERT IGNORE）。 */
    private long ensurePermission(String name) {
        jdbc.update("INSERT IGNORE INTO permissions (name, display_name, scope, created_at, updated_at) "
                + "VALUES (?, ?, 'TEAM', NOW(), NOW())", name, name);
        return jdbc.queryForObject("SELECT id FROM permissions WHERE name = ?", Long.class, name);
    }

    private long insertPermissionGroup(long teamId, String targetRole, boolean deleted, long permissionId) {
        String name = "F0121-G117-group-" + UUID.randomUUID();
        jdbc.update("INSERT INTO permission_groups (team_id, organization_id, target_role, name, created_by, "
                + "deleted_at, created_at, updated_at) VALUES (?, NULL, ?, ?, NULL, "
                + (deleted ? "NOW()" : "NULL") + ", NOW(), NOW())", teamId, targetRole, name);
        long groupId = jdbc.queryForObject("SELECT id FROM permission_groups WHERE name = ?", Long.class, name);
        jdbc.update("INSERT INTO permission_group_permissions (group_id, permission_id, created_at) VALUES (?, ?, NOW())",
                groupId, permissionId);
        return groupId;
    }

    private void assignGroup(long userId, long groupId) {
        jdbc.update("INSERT INTO user_permission_groups (user_id, group_id, assigned_by, created_at) "
                + "VALUES (?, ?, NULL, NOW())", userId, groupId);
    }

    private static FanoutPageRequest page(long scopeId, long cursor, int limit) {
        return new FanoutPageRequest(String.valueOf(scopeId), cursor, limit, false, 0, 1);
    }

    private static List<Long> userIds(List<FanoutRecipient> recipients) {
        return recipients.stream().map(FanoutRecipient::userId).toList();
    }
}
