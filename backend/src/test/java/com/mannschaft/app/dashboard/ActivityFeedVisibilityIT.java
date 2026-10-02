package com.mannschaft.app.dashboard;

import com.mannschaft.app.common.visibility.perf.SqlIntentCounter;
import com.mannschaft.app.dashboard.dto.ActivityFeedPageResponse;
import com.mannschaft.app.dashboard.dto.ActivityFeedResponse;
import com.mannschaft.app.dashboard.entity.ActivityFeedEntity;
import com.mannschaft.app.dashboard.repository.ActivityFeedRepository;
import com.mannschaft.app.dashboard.service.ActivityFeedService;
import com.mannschaft.app.schedule.MinViewRole;
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
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * F03.18 第三隊 — アクティビティフィードの可視性フィルタ結合テスト（Testcontainers / 実 MySQL）。
 *
 * <p>{@code ActivityFeedService} が {@code ScheduleVisibilityResolver} を «実際に» 通し、
 * 予定の可視性ルールどおりにフィード行が隠れることを、モックなしで検証する。
 * 単体テスト（{@code ActivityFeedVisibilityFilterTest}）が測るのは「Resolver の答えに従うか」
 * であり、本 IT が測るのは「答えそのものが正しいか＝誰に見えて誰に見えないか」である。</p>
 *
 * <p>フィクスチャの native INSERT 列構成は
 * {@code ScheduleVisibilityResolverIntegrationTest} を踏襲する
 * （NOT NULL 列をすべて明示し ddl-auto=create-drop 環境でも通す）。</p>
 */
@Transactional
@EnabledIf("com.mannschaft.app.support.test.AbstractMySqlIntegrationTest#isDockerAvailable")
@DisplayName("ActivityFeed 可視性フィルタ — 結合テスト（F03.18）")
class ActivityFeedVisibilityIT extends AbstractMySqlIntegrationTest {

    @Autowired private ActivityFeedService activityFeedService;
    @Autowired private ActivityFeedRepository activityFeedRepository;
    @Autowired private ScheduleRepository scheduleRepository;

    @PersistenceContext
    private EntityManager em;

    private Long actorUserId;
    private Long teamMemberUserId;
    private Long orgOnlyUserId;
    private Long outsiderUserId;
    private Long adminUserId;

    private Long teamId;
    private Long orgId;
    private Long otherOrgId;
    private Long memberRoleId;
    private Long adminRoleId;

    @BeforeEach
    void setUp() {
        memberRoleId = upsertRole("MEMBER", "メンバー", 4);
        adminRoleId = upsertRole("ADMIN", "管理者", 2);

        actorUserId = insertUser("af-vis-actor@test");
        teamMemberUserId = insertUser("af-vis-teammember@test");
        orgOnlyUserId = insertUser("af-vis-orgonly@test");
        outsiderUserId = insertUser("af-vis-outsider@test");
        adminUserId = insertUser("af-vis-admin@test");

        orgId = insertOrganization("AF-Vis-Org");
        otherOrgId = insertOrganization("AF-Vis-OtherOrg");
        teamId = insertTeam("AF-Vis-Team");
        insertTeamOrgMembership(teamId, orgId);

        // teamMember / admin はチーム所属。orgOnly は組織にのみ所属（どのチームにも属さない）。
        insertUserRole(teamMemberUserId, memberRoleId, teamId, null);
        insertUserRole(adminUserId, adminRoleId, teamId, null);
        insertUserRole(orgOnlyUserId, memberRoleId, null, orgId);
        // outsider は «同一組織» には属するが当該チームには属さない（AC-11 の陰性側）。
        insertUserRole(outsiderUserId, memberRoleId, null, orgId);

        em.flush();
        em.clear();
    }

    // ==================================================================
    // AC-11 — チーム所属者に見え、同一組織の非所属者には見えない
    // ==================================================================

    @Test
    @DisplayName("AC-11: チーム所属メンバーには見え、同一組織だがチーム非所属のユーザーには返らない")
    void ac11_teamMemberSeesTeamScheduleOutsiderDoesNot() {
        ScheduleEntity schedule = saveTeamSchedule(ScheduleVisibility.MEMBERS_ONLY, MinViewRole.MEMBER_PLUS);
        saveFeedRow(ActivityType.SCHEDULE_UPDATED, ScopeType.TEAM, teamId, schedule.getId());

        // 陽性: チーム所属者
        List<ActivityFeedResponse> memberView = feedFor(teamMemberUserId, List.of(teamId), List.of(orgId));
        assertThat(memberView)
                .as("チーム所属メンバーに当該予定のフィード行が見えない")
                .extracting(ActivityFeedResponse::getTargetId)
                .contains(schedule.getId());

        // 陰性: 同一組織だが当該チームに非所属
        // （スコープ絞り込みをすり抜けた場合でも Resolver が塞ぐことを確かめるため、
        //   意図的に teamId をスコープに含めて呼ぶ＝可視性フィルタ単独の効き目を測る）
        List<ActivityFeedResponse> outsiderView = feedFor(outsiderUserId, List.of(teamId), List.of(orgId));
        assertThat(outsiderView)
                .as("チーム非所属のユーザーに予定のフィード行が漏れている")
                .extracting(ActivityFeedResponse::getTargetId)
                .doesNotContain(schedule.getId());
    }

    // ==================================================================
    // AC-12 / AC-13 — 参照時判定（作成時スナップショットでない証明）
    // ==================================================================

    @Test
    @DisplayName("AC-12: visibility を狭めると既発行の行が消える。可視な管理者には残る（陽性対照）")
    void ac12_narrowingVisibilityRemovesAlreadyIssuedRow() {
        ScheduleEntity schedule = saveTeamSchedule(ScheduleVisibility.ORGANIZATION, MinViewRole.MEMBER_PLUS);
        saveFeedRow(ActivityType.SCHEDULE_UPDATED, ScopeType.TEAM, teamId, schedule.getId());

        // 変更前: 組織のみ所属のユーザーにも見えている（ORGANIZATION 公開）。
        assertThat(feedFor(orgOnlyUserId, List.of(teamId), List.of(orgId)))
                .as("前提が崩れている: ORGANIZATION 公開時点で組織メンバーに見えていない")
                .extracting(ActivityFeedResponse::getTargetId)
                .contains(schedule.getId());

        // visibility を MEMBERS_ONLY へ引き下げる（＝チーム所属者のみ）。
        narrowVisibility(schedule.getId(), ScheduleVisibility.MEMBERS_ONLY);

        // 可視性を失った側からは «既発行の行» が消える（作成時スナップショットならここで残る）。
        assertThat(feedFor(orgOnlyUserId, List.of(teamId), List.of(orgId)))
                .as("visibility を狭めたのに既発行のフィード行が残っている（作成時スナップショット化＝漏洩）")
                .extracting(ActivityFeedResponse::getTargetId)
                .doesNotContain(schedule.getId());

        // 陽性対照: 引き続き可視なチーム所属者には残っている（フィルタが «全部消して» いない証明）。
        assertThat(feedFor(teamMemberUserId, List.of(teamId), List.of(orgId)))
                .as("引き続き可視であるべきチーム所属者からも消えている（フィルタが効きすぎ）")
                .extracting(ActivityFeedResponse::getTargetId)
                .contains(schedule.getId());
    }

    @Test
    @DisplayName("AC-13: min_view_role を引き上げても AC-12 と同じ2方向を満たす")
    void ac13_raisingMinViewRoleRemovesRowForLowerRole() {
        ScheduleEntity schedule = saveTeamSchedule(ScheduleVisibility.MEMBERS_ONLY, MinViewRole.MEMBER_PLUS);
        saveFeedRow(ActivityType.SCHEDULE_UPDATED, ScopeType.TEAM, teamId, schedule.getId());

        // 前提: MEMBER_PLUS では一般メンバーにも管理者にも見える。
        assertThat(feedFor(teamMemberUserId, List.of(teamId), List.of(orgId)))
                .extracting(ActivityFeedResponse::getTargetId).contains(schedule.getId());
        assertThat(feedFor(adminUserId, List.of(teamId), List.of(orgId)))
                .extracting(ActivityFeedResponse::getTargetId).contains(schedule.getId());

        // 閲覧閾値を ADMIN_ONLY まで引き上げる。
        raiseMinViewRole(schedule.getId(), MinViewRole.ADMIN_ONLY);

        // 陰性: 閾値を下回った一般メンバーから既発行の行が消える。
        assertThat(feedFor(teamMemberUserId, List.of(teamId), List.of(orgId)))
                .as("min_view_role を引き上げたのに一般メンバーへフィード行が残っている（漏洩）")
                .extracting(ActivityFeedResponse::getTargetId)
                .doesNotContain(schedule.getId());

        // 陽性対照: 閾値を満たす管理者には残る。
        assertThat(feedFor(adminUserId, List.of(teamId), List.of(orgId)))
                .as("閾値を満たす管理者からも消えている（フィルタが効きすぎ）")
                .extracting(ActivityFeedResponse::getTargetId)
                .contains(schedule.getId());
    }

    // ==================================================================
    // AC-15 — 削除イベントの例外
    // ==================================================================

    @Test
    @DisplayName("AC-15: 論理削除済み予定の SCHEDULE_CANCELLED は所属者に見え、非所属者には見えない")
    void ac15_cancelledRowVisibleToScopeMembersOnly() {
        ScheduleEntity schedule = saveTeamSchedule(ScheduleVisibility.MEMBERS_ONLY, MinViewRole.MEMBER_PLUS);
        saveFeedRow(ActivityType.SCHEDULE_CANCELLED, ScopeType.TEAM, teamId, schedule.getId());
        softDeleteSchedule(schedule.getId());

        // 陽性: チーム所属者には「削除された事実」が見える。
        // （素通ししないと @SQLRestriction により Resolver の射影から恒久的に落ち、誰にも見えなくなる）
        assertThat(feedFor(teamMemberUserId, List.of(teamId), List.of(orgId)))
                .as("削除済み予定の SCHEDULE_CANCELLED が所属者にも見えない（削除の事実が消えている）")
                .extracting(ActivityFeedResponse::getTargetId)
                .contains(schedule.getId());

        // 陰性: 当該スコープに属さないユーザーには返らない（スコープ絞り込みが唯一の関門）。
        assertThat(feedFor(outsiderUserId, List.of(), List.of(orgId)))
                .as("非所属者に SCHEDULE_CANCELLED が漏れている")
                .extracting(ActivityFeedResponse::getTargetId)
                .doesNotContain(schedule.getId());
    }

    @Test
    @DisplayName("AC-14: 論理削除済み予定の SCHEDULE_UPDATED は所属者にも見えない（fail-closed）")
    void ac14_deletedScheduleNonCancelledRowIsHidden() {
        ScheduleEntity schedule = saveTeamSchedule(ScheduleVisibility.MEMBERS_ONLY, MinViewRole.MEMBER_PLUS);
        saveFeedRow(ActivityType.SCHEDULE_UPDATED, ScopeType.TEAM, teamId, schedule.getId());
        softDeleteSchedule(schedule.getId());

        assertThat(feedFor(teamMemberUserId, List.of(teamId), List.of(orgId)))
                .as("削除済み予定の SCHEDULE_UPDATED（中身を伴う行）が残っている")
                .extracting(ActivityFeedResponse::getTargetId)
                .doesNotContain(schedule.getId());
    }

    // ==================================================================
    // AC-17 / AC-18 — ページ送りに重複・欠落が無い
    // ==================================================================

    @Test
    @DisplayName("AC-17: createdAt を意図的にずらしても、2ページ取得で重複・欠落が0件")
    void ac17_pagingHasNoDuplicatesOrGaps() {
        // 既存7種別（可視性フィルタ非対象）の行を6件作り、createdAt を id 順と «逆» に振る。
        // ORDER BY createdAt DESC のままだとカーソル条件（id < :cursor）と食い違い、
        // ページ境界で行が重複・欠落する。
        List<Long> allIds = new ArrayList<>();
        for (int i = 0; i < 6; i++) {
            ActivityFeedEntity e = saveFeedRow(
                    ActivityType.POST_CREATED, ScopeType.TEAM, teamId, 500L + i,
                    TargetType.TIMELINE_POST);
            allIds.add(e.getId());
            // id が大きいほど createdAt を «古く» する（意図的なねじれ）。
            shiftCreatedAt(e.getId(), LocalDateTime.now().minusMinutes(i * 10L));
        }

        ActivityFeedPageResponse page1 =
                activityFeedService.getActivityFeed(teamMemberUserId, null, 3, List.of(teamId), List.of(orgId));
        assertThat(page1.getItems()).hasSize(3);
        assertThat(page1.getNextCursor()).isNotNull();

        ActivityFeedPageResponse page2 = activityFeedService.getActivityFeed(
                teamMemberUserId, Long.valueOf(page1.getNextCursor()), 3, List.of(teamId), List.of(orgId));

        List<Long> seen = new ArrayList<>();
        page1.getItems().forEach(i -> seen.add(i.getId()));
        page2.getItems().forEach(i -> seen.add(i.getId()));

        assertThat(seen).as("2ページ取得で行が重複している").doesNotHaveDuplicates();
        assertThat(seen).as("2ページ取得で行が欠落している")
                .containsExactlyInAnyOrderElementsOf(allIds);
        // id 降順で整列していること（カーソル条件と整列キーの一致）。
        assertThat(seen).isSortedAccordingTo((a, b) -> Long.compare(b, a));
    }

    // ==================================================================
    // AC-21 — 組織スコープ導出漏れの是正
    // ==================================================================

    @Test
    @DisplayName("AC-21: 組織ロールのみのユーザーに ORGANIZATION スコープの行が返る。別組織には返らない")
    void ac21_organizationScopedRowsAreReturned() {
        ActivityFeedEntity orgRow = saveFeedRow(
                ActivityType.POST_CREATED, ScopeType.ORGANIZATION, orgId, 600L, TargetType.TIMELINE_POST);

        // 陽性: どのチームにも所属しない組織ロールのみのユーザー。
        List<ActivityFeedResponse> orgOnlyView = feedFor(orgOnlyUserId, List.of(), List.of(orgId));
        assertThat(orgOnlyView)
                .as("ORGANIZATION スコープの活動が1件も返らない（組織スコープ導出漏れ）")
                .isNotEmpty();
        assertThat(orgOnlyView).extracting(ActivityFeedResponse::getId).contains(orgRow.getId());
        assertThat(orgOnlyView).extracting(ActivityFeedResponse::getScopeType)
                .contains("ORGANIZATION");

        // 陰性: 別組織のユーザーには返らない。
        List<ActivityFeedResponse> otherOrgView = feedFor(outsiderUserId, List.of(), List.of(otherOrgId));
        assertThat(otherOrgView)
                .as("別組織のユーザーへ組織スコープの活動が漏れている")
                .extracting(ActivityFeedResponse::getId)
                .doesNotContain(orgRow.getId());
    }

    @Test
    @DisplayName("スコープ型別ペアリング: 同じ数値のチームIDでは組織スコープの行を拾わない")
    void scopeTypePairing_teamIdDoesNotMatchOrganizationRow() {
        ActivityFeedEntity orgRow = saveFeedRow(
                ActivityType.POST_CREATED, ScopeType.ORGANIZATION, orgId, 601L, TargetType.TIMELINE_POST);

        // teamIds に «組織の» ID をそのまま渡す。scopeType と対で突き合わせていなければ拾ってしまう。
        List<ActivityFeedResponse> view = feedFor(outsiderUserId, List.of(orgId), List.of());
        assertThat(view)
                .as("チームIDとして渡した数値が ORGANIZATION スコープの行にマッチしている（型跨ぎの漏洩）")
                .extracting(ActivityFeedResponse::getId)
                .doesNotContain(orgRow.getId());
    }

    // ==================================================================
    // AC-23 / AC-24 — schedules へのクエリ意図数
    // ==================================================================

    @Test
    @DisplayName("AC-23: SCHEDULE 系20件のフィードでも schedules へのクエリ意図数は 1")
    void ac23_scheduleQueryIntentIsOne() {
        seedScheduleRows(20);

        em.flush();
        em.clear();
        SqlIntentCounter.reset();

        ActivityFeedPageResponse page = activityFeedService.getActivityFeed(
                teamMemberUserId, null, 20, List.of(teamId), List.of(orgId));
        assertThat(page.getItems()).as("測定対象の行が1件も取れていない（計測が空振り）").isNotEmpty();

        int scheduleIntents = SqlIntentCounter.intentCount("schedules");
        assertThat(scheduleIntents)
                .as("schedules へのクエリ意図数が件数比例している（N+1）。実測=%d, 捕捉SQL=%s",
                        scheduleIntents, SqlIntentCounter.capturedSqls())
                .isEqualTo(1);
    }

    @Test
    @DisplayName("AC-24: 件数を10→50に増やしても総クエリ意図数が増えない（N+1 の不在）")
    void ac24_intentCountDoesNotGrowWithRowCount() {
        seedScheduleRows(10);
        em.flush();
        em.clear();
        SqlIntentCounter.reset();
        activityFeedService.getActivityFeed(teamMemberUserId, null, 10, List.of(teamId), List.of(orgId));
        int intentsFor10 = SqlIntentCounter.intentCount("schedules");

        seedScheduleRows(40); // 合計50件
        em.flush();
        em.clear();
        SqlIntentCounter.reset();
        ActivityFeedPageResponse page50 = activityFeedService.getActivityFeed(
                teamMemberUserId, null, 50, List.of(teamId), List.of(orgId));
        int intentsFor50 = SqlIntentCounter.intentCount("schedules");

        assertThat(page50.getItems()).as("50件側の測定が空振り").isNotEmpty();
        assertThat(intentsFor10).as("10件側の schedules 意図数").isEqualTo(1);
        assertThat(intentsFor50)
                .as("件数を10→50に増やしたら schedules への意図数が増えた（N+1）。10件時=%d, 50件時=%d",
                        intentsFor10, intentsFor50)
                .isEqualTo(intentsFor10);
    }

    // ==================================================================
    // ヘルパ
    // ==================================================================

    /** 指定件数の可視な SCHEDULE 行（予定 + フィード行）を作る。 */
    private void seedScheduleRows(int count) {
        for (int i = 0; i < count; i++) {
            ScheduleEntity s = saveTeamSchedule(ScheduleVisibility.MEMBERS_ONLY, MinViewRole.MEMBER_PLUS);
            saveFeedRow(ActivityType.SCHEDULE_UPDATED, ScopeType.TEAM, teamId, s.getId());
        }
    }

    /** 指定ユーザー・指定スコープでフィードを取得し、items を返す。 */
    private List<ActivityFeedResponse> feedFor(Long viewerUserId, List<Long> teamIds, List<Long> orgIds) {
        em.flush();
        em.clear();
        return activityFeedService.getActivityFeed(viewerUserId, null, 50, teamIds, orgIds).getItems();
    }

    private ActivityFeedEntity saveFeedRow(ActivityType type, ScopeType scopeType, Long scopeId, Long targetId) {
        return saveFeedRow(type, scopeType, scopeId, targetId, TargetType.SCHEDULE);
    }

    private ActivityFeedEntity saveFeedRow(ActivityType type, ScopeType scopeType, Long scopeId,
                                           Long targetId, TargetType targetType) {
        return activityFeedRepository.saveAndFlush(ActivityFeedEntity.builder()
                .scopeType(scopeType)
                .scopeId(scopeId)
                .actorId(actorUserId)
                .activityType(type)
                .targetType(targetType)
                .targetId(targetId)
                .summary("テスト活動")
                .build());
    }

    private ScheduleEntity saveTeamSchedule(ScheduleVisibility visibility, MinViewRole minViewRole) {
        return scheduleRepository.saveAndFlush(ScheduleEntity.builder()
                .teamId(teamId)
                .title("AF-Vis-Schedule")
                .startAt(LocalDateTime.now())
                .endAt(LocalDateTime.now().plusHours(1))
                .allDay(false)
                .eventType(com.mannschaft.app.schedule.EventType.OTHER)
                .visibility(visibility)
                .minViewRole(minViewRole)
                .status(ScheduleStatus.SCHEDULED)
                .attendanceRequired(false)
                .isException(false)
                .createdBy(actorUserId)
                .build());
    }

    /**
     * visibility を直接 UPDATE で狭める。
     * Entity 経由だと {@code @SQLRestriction} や1次キャッシュの影響を受けるため native で書く。
     */
    private void narrowVisibility(Long scheduleId, ScheduleVisibility visibility) {
        em.createNativeQuery("UPDATE schedules SET visibility = :v WHERE id = :id")
                .setParameter("v", visibility.name())
                .setParameter("id", scheduleId)
                .executeUpdate();
        em.flush();
        em.clear();
    }

    private void raiseMinViewRole(Long scheduleId, MinViewRole minViewRole) {
        em.createNativeQuery("UPDATE schedules SET min_view_role = :v WHERE id = :id")
                .setParameter("v", minViewRole.name())
                .setParameter("id", scheduleId)
                .executeUpdate();
        em.flush();
        em.clear();
    }

    /** 論理削除（@SQLRestriction により以後 Resolver の射影に載らなくなる）。 */
    private void softDeleteSchedule(Long scheduleId) {
        em.createNativeQuery("UPDATE schedules SET deleted_at = NOW() WHERE id = :id")
                .setParameter("id", scheduleId)
                .executeUpdate();
        em.flush();
        em.clear();
    }

    private void shiftCreatedAt(Long feedId, LocalDateTime createdAt) {
        em.createNativeQuery("UPDATE activity_feed SET created_at = :ts WHERE id = :id")
                .setParameter("ts", createdAt)
                .setParameter("id", feedId)
                .executeUpdate();
        em.flush();
        em.clear();
    }

    private Long upsertRole(String name, String displayName, int priority) {
        em.createNativeQuery(
                "INSERT INTO roles (name, display_name, priority, is_system, created_at, updated_at) "
                        + "VALUES (:n, :d, :p, 0, NOW(), NOW()) ON DUPLICATE KEY UPDATE id = id")
                .setParameter("n", name)
                .setParameter("d", displayName)
                .setParameter("p", priority)
                .executeUpdate();
        em.flush();
        return ((Number) em.createNativeQuery("SELECT id FROM roles WHERE name = :n")
                .setParameter("n", name).getSingleResult()).longValue();
    }

    private Long insertUser(String email) {
        em.createNativeQuery(
                "INSERT INTO users ("
                        + "email, last_name, first_name, display_name, status, "
                        + "is_searchable, handle_searchable, contact_approval_required, "
                        + "online_visibility, dm_receive_from, encryption_key_version, "
                        + "locale, timezone, reporting_restricted, follow_list_visibility, "
                        + "care_notification_enabled, offline_only, created_at, updated_at) "
                        + "VALUES (:email, 'Test', 'User', 'Test User', 'ACTIVE', "
                        + "1, 1, 1, 'NOBODY', 'ANYONE', 1, 'ja', 'Asia/Tokyo', 0, 'PUBLIC', "
                        + "1, 0, NOW(), NOW())")
                .setParameter("email", email)
                .executeUpdate();
        return ((Number) em.createNativeQuery("SELECT id FROM users WHERE email = :email")
                .setParameter("email", email).getSingleResult()).longValue();
    }

    private Long insertOrganization(String name) {
        em.createNativeQuery(
                "INSERT INTO organizations (name, org_type, visibility, hierarchy_visibility, "
                        + "supporter_enabled, version, slug, created_at, updated_at) "
                        + "VALUES (:name, 'OTHER', 'PUBLIC', 'NONE', 1, 0, "
                        + "CONCAT('s-', LEFT(REPLACE(UUID(),'-',''),8)), NOW(), NOW())")
                .setParameter("name", name)
                .executeUpdate();
        return ((Number) em.createNativeQuery("SELECT id FROM organizations WHERE name = :name")
                .setParameter("name", name).getSingleResult()).longValue();
    }

    private Long insertTeam(String name) {
        em.createNativeQuery(
                "INSERT INTO teams (name, visibility, supporter_enabled, version, member_count, slug, "
                        + "created_at, updated_at) "
                        + "VALUES (:name, 'PUBLIC', 1, 0, 0, "
                        + "CONCAT('s-', LEFT(REPLACE(UUID(),'-',''),8)), NOW(), NOW())")
                .setParameter("name", name)
                .executeUpdate();
        return ((Number) em.createNativeQuery("SELECT id FROM teams WHERE name = :name")
                .setParameter("name", name).getSingleResult()).longValue();
    }

    private void insertTeamOrgMembership(Long tid, Long oid) {
        em.createNativeQuery(
                "INSERT INTO team_org_memberships (team_id, organization_id, status, invited_at, created_at) "
                        + "VALUES (:tid, :oid, 'ACTIVE', NOW(), NOW())")
                .setParameter("tid", tid).setParameter("oid", oid).executeUpdate();
    }

    private void insertUserRole(Long uid, Long roleId, Long tid, Long oid) {
        em.createNativeQuery(
                "INSERT INTO user_roles (user_id, role_id, team_id, organization_id, created_at, updated_at) "
                        + "VALUES (:uid, :rid, :tid, :oid, NOW(), NOW())")
                .setParameter("uid", uid).setParameter("rid", roleId)
                .setParameter("tid", tid).setParameter("oid", oid)
                .executeUpdate();
    }
}
