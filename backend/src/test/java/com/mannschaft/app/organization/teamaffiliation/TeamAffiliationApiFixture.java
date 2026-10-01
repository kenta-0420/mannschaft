package com.mannschaft.app.organization.teamaffiliation;

import com.mannschaft.app.membership.domain.RoleKind;
import com.mannschaft.app.organization.entity.OrganizationEntity;
import com.mannschaft.app.organization.teamgroup.entity.OrgTeamGroupEntity;
import com.mannschaft.app.role.entity.PermissionGroupEntity;
import com.mannschaft.app.role.entity.PermissionGroupPermissionEntity;
import com.mannschaft.app.role.entity.UserPermissionGroupEntity;
import com.mannschaft.app.team.entity.TeamEntity;
import com.mannschaft.app.team.entity.TeamOrgAffiliationDirection;
import com.mannschaft.app.team.entity.TeamOrgAffiliationRestrictionEntity;
import com.mannschaft.app.team.entity.TeamOrgAffiliationRestrictionKind;
import com.mannschaft.app.team.entity.TeamOrgAffiliationRestrictionReason;
import com.mannschaft.app.team.entity.TeamOrgMembershipEntity;
import com.mannschaft.app.membership.domain.ScopeType;
import com.mannschaft.app.support.test.MembershipTestHelper;
import jakarta.persistence.EntityManager;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * F01.2.1 部隊 2-A の IT が共有する固定人物と組織・チームのフィクスチャ。
 *
 * <p>人物は設計書 §16 の記号に揃える。XA＝組織X の ADMIN、XD＝組織X の DEPUTY_ADMIN、XM＝組織X の MEMBER、
 * TA＝チームT の ADMIN、TD＝チームT の DEPUTY_ADMIN（加盟操作権限なし）、TM＝チームT の MEMBER（権限なし）、
 * TG＝チームT の MEMBER で権限グループにより {@code MANAGE_ORG_AFFILIATION} を付与された人、
 * YA＝組織Y の ADMIN、N＝どこにも属さない認証済みユーザー、SA＝SYSTEM_ADMIN。</p>
 *
 * <p>本番 DB と同じ形を作るため、所属は {@code memberships}、権限ロールは {@code user_roles} に置く
 * （{@link MembershipTestHelper} の作法）。共有コンテキストは ddl-auto=create・Flyway 無効なので、
 * {@code permissions} の行は Flyway V231 と同じ内容を冪等に seed する。</p>
 *
 * <p>行は Entity 経由で作る（Hibernate が生成したスキーマの NOT NULL 列を取りこぼさないため）。</p>
 */
final class TeamAffiliationApiFixture {

    static final Long XA = 930260101L;
    static final Long XD = 930260102L;
    static final Long XM = 930260103L;
    static final Long TA = 930260104L;
    static final Long TD = 930260105L;
    static final Long TM = 930260106L;
    static final Long TG = 930260107L;
    static final Long YA = 930260108L;
    static final Long N = 930260109L;
    static final Long SA = 930260110L;

    private static final AtomicInteger SEQ = new AtomicInteger();

    private final EntityManager em;
    private final String nonce;

    /** 組織X（公開・受付の既定値は off）。 */
    Long orgXId;
    String orgXSlug;
    /** 組織P（非公開。XA〜TG・YA・N のいずれも所属しない）。 */
    Long orgPId;
    String orgPSlug;
    /** 組織Y（公開。YA が ADMIN）。 */
    Long orgYId;
    String orgYSlug;
    /** 存在しない組織の slug。 */
    String absentSlug;

    /** チームT（TA・TD・TM・TG が所属）。 */
    Long teamTId;
    String teamTSlug;

    TeamAffiliationApiFixture(EntityManager em) {
        this.em = em;
        this.nonce = Long.toUnsignedString(System.nanoTime(), Character.MAX_RADIX)
                + SEQ.incrementAndGet();
    }

    /** 固定人物・組織X/P/Y・チームT を作る。 */
    TeamAffiliationApiFixture seed() {
        orgXSlug = slug("afx");
        orgPSlug = slug("afp");
        orgYSlug = slug("afy");
        absentSlug = slug("afz");
        orgXId = insertOrganization("加盟受付テスト組織X" + nonce, orgXSlug, "PUBLIC");
        orgPId = insertOrganization("加盟受付テスト非公開組織P" + nonce, orgPSlug, "PRIVATE");
        orgYId = insertOrganization("加盟受付テスト組織Y" + nonce, orgYSlug, "PUBLIC");

        teamTSlug = slug("aft");
        teamTId = insertTeam("加盟受付テストチームT" + nonce, teamTSlug);

        for (Long userId : new Long[] {XA, XD, XM, TA, TD, TM, TG, YA, N, SA}) {
            MembershipTestHelper.insertActiveUser(em, userId);
        }
        // 組織X: XA=ADMIN、XD=DEPUTY_ADMIN、XM=MEMBER（所属は memberships、権限ロールは user_roles）
        MembershipTestHelper.insertMembership(em, XA, ScopeType.ORGANIZATION, orgXId, RoleKind.MEMBER);
        MembershipTestHelper.insertUserRole(em, XA, "ADMIN", null, orgXId);
        MembershipTestHelper.insertMembership(em, XD, ScopeType.ORGANIZATION, orgXId, RoleKind.MEMBER);
        MembershipTestHelper.insertUserRole(em, XD, "DEPUTY_ADMIN", null, orgXId);
        MembershipTestHelper.insertMembership(em, XM, ScopeType.ORGANIZATION, orgXId, RoleKind.MEMBER);
        // 組織Y: YA=ADMIN
        MembershipTestHelper.insertMembership(em, YA, ScopeType.ORGANIZATION, orgYId, RoleKind.MEMBER);
        MembershipTestHelper.insertUserRole(em, YA, "ADMIN", null, orgYId);
        // チームT: TA=ADMIN、TD=DEPUTY_ADMIN、TM=MEMBER、TG=MEMBER（権限グループで付与）
        for (Long userId : new Long[] {TA, TD, TM, TG}) {
            MembershipTestHelper.insertMembership(em, userId, ScopeType.TEAM, teamTId, RoleKind.MEMBER);
        }
        MembershipTestHelper.insertUserRole(em, TA, "ADMIN", teamTId, null);
        MembershipTestHelper.insertUserRole(em, TD, "DEPUTY_ADMIN", teamTId, null);
        grantAffiliationByPermissionGroup(TG, teamTId, "MEMBER");
        // SA: SYSTEM_ADMIN（どの組織にも所属しない）
        MembershipTestHelper.insertUserRole(em, SA, "SYSTEM_ADMIN", null, null);

        em.flush();
        em.clear();
        return this;
    }

    String slug(String prefix) {
        return prefix + "-" + nonce + "-" + SEQ.incrementAndGet();
    }

    Long insertOrganization(String name, String slug, String visibility) {
        OrganizationEntity org = OrganizationEntity.builder()
                .slug(slug)
                .name(name)
                .orgType(OrganizationEntity.OrgType.OTHER)
                .visibility(OrganizationEntity.Visibility.valueOf(visibility))
                .hierarchyVisibility(OrganizationEntity.HierarchyVisibility.NONE)
                .supporterEnabled(false)
                .build();
        em.persist(org);
        em.flush();
        return org.getId();
    }

    Long insertTeam(String name, String slug) {
        TeamEntity team = TeamEntity.builder()
                .slug(slug)
                .name(name)
                .visibility(TeamEntity.Visibility.MEMBERS_AND_ABOVE)
                .supporterEnabled(true)
                .build();
        em.persist(team);
        em.flush();
        return team.getId();
    }

    /** 受付・グループ機能・保存モードを DB へ直接書く（前提状態の準備）。 */
    void setOrgSettings(Long orgId, boolean applicationEnabled, boolean groupsEnabled, String groupMode) {
        em.createNativeQuery("UPDATE organizations SET team_application_enabled = :a, "
                        + "team_groups_enabled = :g, team_application_group_mode = :m WHERE id = :id")
                .setParameter("a", applicationEnabled)
                .setParameter("g", groupsEnabled)
                .setParameter("m", groupMode)
                .setParameter("id", orgId)
                .executeUpdate();
        em.flush();
        em.clear();
    }

    void setGuidance(Long orgId, String guidance) {
        em.createNativeQuery("UPDATE organizations SET team_application_guidance = :g WHERE id = :id")
                .setParameter("g", guidance)
                .setParameter("id", orgId)
                .executeUpdate();
        em.flush();
        em.clear();
    }

    /** 生存しているチームグループを1件作り、その UUID を返す。 */
    UUID insertTeamGroup(Long orgId, String name, String description, int sortOrder) {
        OrgTeamGroupEntity group = OrgTeamGroupEntity.builder()
                .organizationId(orgId)
                .name(name)
                .description(description)
                .sortOrder(sortOrder)
                .build();
        em.persist(group);
        em.flush();
        return group.getId();
    }

    void softDeleteTeamGroup(UUID groupId) {
        em.createNativeQuery("UPDATE org_team_groups SET deleted_at = UTC_TIMESTAMP() WHERE id = UUID_TO_BIN(:id)")
                .setParameter("id", groupId.toString())
                .executeUpdate();
        em.flush();
        em.clear();
    }

    /** team_org_memberships へ1行入れる（status=PENDING/ACTIVE、direction=ORG_INVITE/TEAM_APPLY）。 */
    void insertTeamOrgMembership(Long teamId, Long orgId, String status, String direction) {
        em.persist(TeamOrgMembershipEntity.builder()
                .teamId(teamId)
                .organizationId(orgId)
                .status(TeamOrgMembershipEntity.Status.valueOf(status))
                .direction(TeamOrgAffiliationDirection.valueOf(direction))
                .invitedAt(LocalDateTime.now())
                .build());
        em.flush();
    }

    /** 申請方向（TEAM_APPLY）の制限を1行入れる。kind=COOLDOWN なら期限は未来、BLOCK なら NULL。 */
    void insertApplyRestriction(Long teamId, Long orgId, String kind, String reason) {
        TeamOrgAffiliationRestrictionKind k = TeamOrgAffiliationRestrictionKind.valueOf(kind);
        em.persist(TeamOrgAffiliationRestrictionEntity.builder()
                .organizationId(orgId)
                .teamId(teamId)
                .direction(TeamOrgAffiliationDirection.TEAM_APPLY)
                .kind(k)
                .reason(TeamOrgAffiliationRestrictionReason.valueOf(reason))
                .restrictedUntil(k == TeamOrgAffiliationRestrictionKind.COOLDOWN
                        ? Instant.now().plus(Duration.ofDays(30)) : null)
                .build());
        em.flush();
    }

    /**
     * 権限グループ（target_role 指定）に {@code MANAGE_ORG_AFFILIATION} を含め、userId に割り当てる
     * （§3.2 の付与経路）。
     */
    void grantAffiliationByPermissionGroup(Long userId, Long teamId, String targetRole) {
        Long permissionId = ensurePermission("MANAGE_ORG_AFFILIATION", "組織への加盟操作");
        PermissionGroupEntity group = PermissionGroupEntity.builder()
                .teamId(teamId)
                .name("加盟操作" + nonce + SEQ.incrementAndGet())
                .targetRole(PermissionGroupEntity.TargetRole.valueOf(targetRole))
                .build();
        em.persist(group);
        em.persist(PermissionGroupPermissionEntity.builder()
                .groupId(group.getId())
                .permissionId(permissionId)
                .build());
        em.persist(UserPermissionGroupEntity.builder()
                .userId(userId)
                .groupId(group.getId())
                .build());
        em.flush();
    }

    private Long ensurePermission(String name, String displayName) {
        em.createNativeQuery(
                        "INSERT INTO permissions (name, display_name, scope, created_at, updated_at) "
                                + "SELECT :name, :displayName, 'TEAM', UTC_TIMESTAMP(), UTC_TIMESTAMP() FROM DUAL "
                                + "WHERE NOT EXISTS (SELECT 1 FROM permissions WHERE name = :name)")
                .setParameter("name", name)
                .setParameter("displayName", displayName)
                .executeUpdate();
        return ((Number) em.createNativeQuery("SELECT id FROM permissions WHERE name = :name")
                .setParameter("name", name)
                .getSingleResult()).longValue();
    }

    /** 応答本文から要求 slug 由来の文字列と時刻値を除き、本質的な形だけを比較できるようにする。 */
    static String normalize(String body, String slug) {
        return body.replace(slug, "<slug>")
                .replaceAll("\\d{4}-\\d{2}-\\d{2}T[0-9:.]+(Z|[+-]\\d{2}:\\d{2})?", "<time>");
    }
}
