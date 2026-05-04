package com.mannschaft.app.schedule.visibility;

import com.mannschaft.app.common.visibility.ContentVisibilityChecker;
import com.mannschaft.app.common.visibility.ReferenceType;
import com.mannschaft.app.schedule.ScheduleStatus;
import com.mannschaft.app.schedule.ScheduleVisibility;
import com.mannschaft.app.schedule.entity.ScheduleEntity;
import com.mannschaft.app.schedule.repository.ScheduleRepository;
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
 * {@link ScheduleVisibilityResolver} の結合テスト（Testcontainers / 実 MySQL）。
 *
 * <p>F00 Phase B — Resolver が JPQL Projection で実際の {@code schedules} テーブルから
 * 1 SQL で射影を取得し、{@link com.mannschaft.app.common.visibility.MembershipBatchQueryService}
 * 経由の snapshot と組み合わせて正しく判定することを検証する。</p>
 *
 * <p>本テストは {@link AbstractMySqlIntegrationTest} を継承し、Spring TestContext を
 * 全結合テスト共通のものに揃えてキャッシュ分裂を防ぐ。</p>
 *
 * <p>seed の native INSERT 列構成は
 * {@link com.mannschaft.app.common.visibility.MembershipBatchQueryServiceIntegrationTest}
 * のパターンを踏襲（NOT NULL 列をすべて明示することで ddl-auto=create-drop 環境でも通る）。</p>
 */
@Transactional
@EnabledIf("com.mannschaft.app.support.test.AbstractMySqlIntegrationTest#isDockerAvailable")
@DisplayName("ScheduleVisibilityResolver — 結合テスト")
class ScheduleVisibilityResolverIntegrationTest extends AbstractMySqlIntegrationTest {

    @Autowired
    private ScheduleVisibilityResolver resolver;

    @Autowired
    private ScheduleRepository scheduleRepository;

    @Autowired
    private ContentVisibilityChecker contentVisibilityChecker;

    @PersistenceContext
    private EntityManager em;

    private Long ownerUserId;
    private Long otherUserId;
    private Long teamMemberUserId;
    private Long orgMemberUserId;
    private Long teamId;
    private Long orgId;
    private Long memberRoleId;

    @BeforeEach
    void setUp() {
        // ロール
        em.createNativeQuery(
                "INSERT INTO roles (name, display_name, priority, is_system, created_at, updated_at) "
                        + "VALUES ('MEMBER', 'メンバー', 4, 0, NOW(), NOW())")
                .executeUpdate();
        em.flush();
        memberRoleId = ((Number) em.createNativeQuery(
                "SELECT id FROM roles WHERE name = 'MEMBER'").getSingleResult()).longValue();

        // ユーザー
        ownerUserId = insertUser("schedule-vr-owner@test", "Owner", "T");
        otherUserId = insertUser("schedule-vr-other@test", "Other", "T");
        teamMemberUserId = insertUser("schedule-vr-team@test", "Team", "M");
        orgMemberUserId = insertUser("schedule-vr-org@test", "Org", "M");

        // 組織・チーム
        orgId = insertOrganization("Schedule-VR-Org");
        teamId = insertTeam("Schedule-VR-Team");
        insertTeamOrgMembership(teamId, orgId);

        // メンバーシップ
        insertUserRole(teamMemberUserId, memberRoleId, teamId, null);
        insertUserRole(orgMemberUserId, memberRoleId, null, orgId);

        em.flush();
        em.clear();
    }

    @Test
    @DisplayName("PERSONAL スコープ — 作成者本人は可視、他人は不可視")
    void personalScope_ownerOnly() {
        ScheduleEntity personal = saveSchedule(/*team*/ null, /*org*/ null,
                /*user*/ ownerUserId, ScheduleVisibility.MEMBERS_ONLY,
                /*createdBy*/ ownerUserId);

        assertThat(resolver.canView(personal.getId(), ownerUserId)).isTrue();
        assertThat(resolver.canView(personal.getId(), otherUserId)).isFalse();
    }

    @Test
    @DisplayName("TEAM × MEMBERS_ONLY — チームメンバーは可視、非メンバーは不可視")
    void teamMembersOnly() {
        ScheduleEntity teamSchedule = saveSchedule(teamId, null, null,
                ScheduleVisibility.MEMBERS_ONLY, ownerUserId);

        assertThat(resolver.canView(teamSchedule.getId(), teamMemberUserId)).isTrue();
        assertThat(resolver.canView(teamSchedule.getId(), otherUserId)).isFalse();
    }

    @Test
    @DisplayName("ORGANIZATION スコープ × ORGANIZATION — 親 ORG メンバーは可視")
    void orgOrganizationWide() {
        ScheduleEntity orgSchedule = saveSchedule(null, orgId, null,
                ScheduleVisibility.ORGANIZATION, ownerUserId);

        assertThat(resolver.canView(orgSchedule.getId(), orgMemberUserId)).isTrue();
        assertThat(resolver.canView(orgSchedule.getId(), otherUserId)).isFalse();
    }

    @Test
    @DisplayName("filterAccessible — 複数 ID をバッチで判定")
    void batchFilterAccessible() {
        ScheduleEntity teamSched = saveSchedule(teamId, null, null,
                ScheduleVisibility.MEMBERS_ONLY, ownerUserId);
        ScheduleEntity ownerPersonal = saveSchedule(null, null, ownerUserId,
                ScheduleVisibility.MEMBERS_ONLY, ownerUserId);
        ScheduleEntity otherPersonal = saveSchedule(null, null, otherUserId,
                ScheduleVisibility.MEMBERS_ONLY, otherUserId);

        Set<Long> visible = resolver.filterAccessible(
                List.of(teamSched.getId(), ownerPersonal.getId(), otherPersonal.getId()),
                ownerUserId);

        // owner: PERSONAL は自分の ownerPersonal のみ可視。
        // teamSched は owner がチーム所属していないため不可視。
        assertThat(visible).containsExactlyInAnyOrder(ownerPersonal.getId());

        Set<Long> teamMemberView = resolver.filterAccessible(
                List.of(teamSched.getId(), ownerPersonal.getId(), otherPersonal.getId()),
                teamMemberUserId);
        // teamMember はチームメンバー → teamSched 可視。PERSONAL は他人 → 不可視。
        assertThat(teamMemberView).containsExactlyInAnyOrder(teamSched.getId());
    }

    @Test
    @DisplayName("ContentVisibilityChecker.canView 経由で SCHEDULE 判定が成立する")
    void checker_canView_dispatchesToScheduleResolver() {
        ScheduleEntity teamSched = saveSchedule(teamId, null, null,
                ScheduleVisibility.MEMBERS_ONLY, ownerUserId);

        assertThat(contentVisibilityChecker.canView(
                ReferenceType.SCHEDULE, teamSched.getId(), teamMemberUserId)).isTrue();
        assertThat(contentVisibilityChecker.canView(
                ReferenceType.SCHEDULE, teamSched.getId(), otherUserId)).isFalse();
    }

    @Test
    @DisplayName("不存在の ID は NOT_FOUND（IDOR 防止）")
    void notFound_idor() {
        assertThat(resolver.canView(999_999L, ownerUserId)).isFalse();
        assertThat(contentVisibilityChecker.canView(
                ReferenceType.SCHEDULE, 999_999L, ownerUserId)).isFalse();
    }

    // ========================================================================
    // セットアップヘルパ
    // ========================================================================

    private ScheduleEntity saveSchedule(Long teamId, Long orgId, Long userId,
                                        ScheduleVisibility visibility, Long createdBy) {
        ScheduleEntity entity = ScheduleEntity.builder()
                .teamId(teamId)
                .organizationId(orgId)
                .userId(userId)
                .title("test-schedule")
                .startAt(LocalDateTime.now())
                .endAt(LocalDateTime.now().plusHours(1))
                .allDay(false)
                .eventType(com.mannschaft.app.schedule.EventType.OTHER)
                .visibility(visibility)
                .minViewRole(com.mannschaft.app.schedule.MinViewRole.MEMBER_PLUS)
                .status(ScheduleStatus.SCHEDULED)
                .attendanceRequired(false)
                .isException(false)
                .createdBy(createdBy)
                .build();
        // saveAndFlush で MySQL auto_increment ID を即時確定させ、
        // entity.getId() が null にならないようにする (NPE 防止)。
        return scheduleRepository.saveAndFlush(entity);
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
                        + "supporter_enabled, version, created_at, updated_at) "
                        + "VALUES (:name, 'OTHER', 'PUBLIC', 'NONE', 1, 0, NOW(), NOW())")
                .setParameter("name", name)
                .executeUpdate();
        return ((Number) em.createNativeQuery(
                "SELECT id FROM organizations WHERE name = :name")
                .setParameter("name", name)
                .getSingleResult()).longValue();
    }

    private Long insertTeam(String name) {
        em.createNativeQuery(
                "INSERT INTO teams (name, visibility, supporter_enabled, version, created_at, updated_at) "
                        + "VALUES (:name, 'PUBLIC', 1, 0, NOW(), NOW())")
                .setParameter("name", name)
                .executeUpdate();
        return ((Number) em.createNativeQuery(
                "SELECT id FROM teams WHERE name = :name")
                .setParameter("name", name)
                .getSingleResult()).longValue();
    }

    private void insertTeamOrgMembership(Long teamId, Long orgId) {
        em.createNativeQuery(
                "INSERT INTO team_org_memberships (team_id, organization_id, status, "
                        + "invited_at, created_at) "
                        + "VALUES (:tid, :oid, 'ACTIVE', NOW(), NOW())")
                .setParameter("tid", teamId)
                .setParameter("oid", orgId)
                .executeUpdate();
    }

    private void insertUserRole(Long uid, Long roleId, Long teamId, Long orgId) {
        em.createNativeQuery(
                "INSERT INTO user_roles (user_id, role_id, team_id, organization_id, "
                        + "created_at, updated_at) "
                        + "VALUES (:uid, :rid, :tid, :oid, NOW(), NOW())")
                .setParameter("uid", uid)
                .setParameter("rid", roleId)
                .setParameter("tid", teamId)
                .setParameter("oid", orgId)
                .executeUpdate();
    }
}
