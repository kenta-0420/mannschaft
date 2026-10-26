package com.mannschaft.app.common.visibility;

import com.mannschaft.app.membership.domain.RoleKind;
import com.mannschaft.app.membership.domain.ScopeType;
import com.mannschaft.app.schedule.EventType;
import com.mannschaft.app.schedule.MinViewRole;
import com.mannschaft.app.schedule.ScheduleStatus;
import com.mannschaft.app.schedule.ScheduleVisibility;
import com.mannschaft.app.schedule.entity.ScheduleEntity;
import com.mannschaft.app.schedule.repository.ScheduleRepository;
import com.mannschaft.app.schedule.service.GoogleApiClient;
import com.mannschaft.app.schedule.service.GoogleCalendarWebhookService;
import com.mannschaft.app.support.test.AbstractMySqlIntegrationTest;
import com.mannschaft.app.support.test.MembershipTestHelper;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.hibernate.SessionFactory;
import org.hibernate.stat.Statistics;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * F01.2.1 §9.2 #2〜#6（部隊 3-A）— 可視性の複数親組織対応の試練（先行 red）。
 *
 * <h2>前提フィクスチャ</h2>
 * <p>チーム T が組織 X と組織 Y の両方に ACTIVE で加盟している（§16 N 群の前提）。
 * 書き込み API がまだ無いため team_org_memberships は native INSERT で直接作る。
 * Resolver・Repository・Security はモックしない（外部 Google API だけは既存の予定 IT と同じく遮断し、
 * {@code ScheduleMinViewRoleContractIT} と同一構成にして TestContext を共有する）。</p>
 *
 * <h2>ORGANIZATION_WIDE の意味（本テストでの読み方）</h2>
 * <p>{@link StandardVisibility#ORGANIZATION_WIDE} は「TEAM スコープのコンテンツを、そのチームの
 * <strong>親組織のメンバー</strong>へ上向き1段で公開する」段である（予定では
 * {@code visibility = ORGANIZATION}）。したがって「X 宛ての ORGANIZATION_WIDE」とは、
 * T の ORGANIZATION_WIDE コンテンツを X の組織メンバーが T 経由で見る経路を指す。</p>
 *
 * <h2>現状の欠陥を決定的に撃つ設計</h2>
 * <p>現行実装は {@code TeamOrgMembershipRepository.findOrganizationIdByTeamIdIn} の
 * {@code HashMap.put} 後勝ちで、T の親組織を X か Y の<strong>どちらか1件</strong>に潰す
 * （どちらになるかは DB の返す行順次第）。そこで各テストは<strong>X 側と Y 側の両方</strong>を
 * 1メソッド内で検証する。どちらが残っても、潰された側の検証が必ず失敗するため、
 * 行順という非決定性に依存せず red になる。</p>
 */
@AutoConfigureMockMvc(addFilters = false)
@Transactional
@EnabledIf("com.mannschaft.app.support.test.AbstractMySqlIntegrationTest#isDockerAvailable")
@DisplayName("F01.2.1 3-A 可視性の複数親組織対応（試練）")
class TeamMultiParentOrgVisibilityIT extends AbstractMySqlIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ScheduleRepository scheduleRepository;

    @Autowired
    private ScopeAncestorResolver scopeAncestorResolver;

    @Autowired
    private MembershipBatchQueryService membershipBatchQueryService;

    @Autowired
    private ContentVisibilityChecker contentVisibilityChecker;

    /** 外部 API 呼び出しは本テストの対象外のため遮断する（ScheduleMinViewRoleContractIT と同一構成）。 */
    @MockitoBean
    private GoogleApiClient googleApiClient;

    @MockitoBean
    private GoogleCalendarWebhookService googleCalendarWebhookService;

    @PersistenceContext
    private EntityManager em;

    private String sfx;

    private Long orgX;
    private Long orgY;
    /** T が加盟していない組織。 */
    private Long orgZ;
    /** X と Y の両方に ACTIVE で加盟するチーム。 */
    private Long teamT;
    private String teamTSlug;
    /** どの組織にも加盟していないチーム（G131）。 */
    private Long teamOrphan;
    private String teamOrphanSlug;

    /** T の DEPUTY_ADMIN（予定の作成者）。 */
    private Long teamAuthorId;
    /** X にだけ直接所属する MEMBER（T には所属しない）。 */
    private Long xMemberId;
    /** Y にだけ直接所属する MEMBER（T には所属しない）。 */
    private Long yMemberId;
    /** Z にだけ直接所属する MEMBER。 */
    private Long zMemberId;

    @BeforeEach
    void setUp() {
        sfx = String.valueOf(System.nanoTime());
        orgX = insertOrganization("MP可視 組織X " + sfx, "mpv-x-" + sfx);
        orgY = insertOrganization("MP可視 組織Y " + sfx, "mpv-y-" + sfx);
        orgZ = insertOrganization("MP可視 組織Z " + sfx, "mpv-z-" + sfx);

        teamTSlug = "mpv-t-" + sfx;
        teamT = insertTeam("MP可視 チームT " + sfx, teamTSlug);
        teamOrphanSlug = "mpv-o-" + sfx;
        teamOrphan = insertTeam("MP可視 チーム無所属 " + sfx, teamOrphanSlug);

        // 前提: T は X と Y の両方に ACTIVE で加盟（X が先に成立）。
        linkTeamToOrganization(teamT, orgX, LocalDateTime.of(2026, 4, 1, 9, 0));
        linkTeamToOrganization(teamT, orgY, LocalDateTime.of(2026, 4, 2, 9, 0));

        teamAuthorId = insertUser("mpv-author-" + sfx + "@example.com");
        MembershipTestHelper.insertMembership(em, teamAuthorId, ScopeType.TEAM, teamT, RoleKind.MEMBER);
        MembershipTestHelper.insertUserRole(em, teamAuthorId, "DEPUTY_ADMIN", teamT, null);

        xMemberId = newOrgMember("x", orgX);
        yMemberId = newOrgMember("y", orgY);
        zMemberId = newOrgMember("z", orgZ);

        em.flush();
        em.clear();
    }

    // =========================================================================
    // AC-N01: ScopeAncestorResolver が X と Y の両方を返す
    // =========================================================================

    @Test
    @DisplayName("AC-N01 ScopeAncestorResolver に T を渡すと親組織として {X, Y} の両方が返る（後勝ちで1つに潰れない）")
    void N01_親組織として両方が返る() {
        ScopeKey t = new ScopeKey("TEAM", teamT);

        Map<ScopeKey, ?> result = scopeAncestorResolver.resolveParentOrgIds(Set.of(t));

        // §9.2 #2: 戻り値は Map<ScopeKey, Set<Long>> になる。現行の Map<ScopeKey, Long> では
        // X か Y の片方の Long しか入らず、集合 {X, Y} と一致しない（決定的に red）。
        assertThat((Object) result.get(t))
                .as("T の親組織は X と Y の集合であるべし")
                .isEqualTo(Set.of(orgX, orgY));
    }

    // =========================================================================
    // AC-N02: X と Y の ORGANIZATION_WIDE は見え、Z は見えない
    // =========================================================================

    @Test
    @DisplayName("AC-N02 T の ORGANIZATION_WIDE 予定は X のメンバーにも Y のメンバーにも 200、Z のメンバーには 403")
    void N02_両方の親組織メンバーが閲覧でき_無関係の組織は閲覧できない() throws Exception {
        Long scheduleId = saveTeamSchedule(teamT, ScheduleVisibility.ORGANIZATION, MinViewRole.MEMBER_PLUS);

        // 片方の親に潰れる現行実装では、潰された側のメンバーが 403 になる（決定的に red）。
        List<String> failures = new ArrayList<>();
        expectStatus(failures, "X のメンバー", xMemberId, teamTSlug, scheduleId, 200);
        expectStatus(failures, "Y のメンバー", yMemberId, teamTSlug, scheduleId, 200);
        expectStatus(failures, "Z のメンバー", zMemberId, teamTSlug, scheduleId, 403);
        assertThat(failures).as("複数親の ORGANIZATION_WIDE 閲覧").isEmpty();
    }

    // =========================================================================
    // AC-C04(c): 両組織の ORGANIZATION_WIDE 投稿を閲覧できる（バッチ経路）
    // =========================================================================

    @Test
    @DisplayName("AC-C04(c) 2組織へ加盟した T の ORGANIZATION_WIDE 予定は、バッチ判定（filterAccessible）でも両組織のメンバーに残る")
    void C04c_バッチ経路でも両組織のメンバーが閲覧できる() {
        Long orgWide = saveTeamSchedule(teamT, ScheduleVisibility.ORGANIZATION, MinViewRole.MEMBER_PLUS);
        Long membersOnly = saveTeamSchedule(teamT, ScheduleVisibility.MEMBERS_ONLY, MinViewRole.MEMBER_PLUS);
        Collection<Long> ids = List.of(orgWide, membersOnly);

        Set<Long> forX = contentVisibilityChecker.filterAccessible(ReferenceType.SCHEDULE, ids, xMemberId);
        Set<Long> forY = contentVisibilityChecker.filterAccessible(ReferenceType.SCHEDULE, ids, yMemberId);
        Set<Long> forZ = contentVisibilityChecker.filterAccessible(ReferenceType.SCHEDULE, ids, zMemberId);

        assertThat(Map.of("X", forX, "Y", forY))
                .as("X・Y どちらのメンバーにも ORGANIZATION_WIDE 予定だけが残るべし")
                .isEqualTo(Map.of("X", Set.of(orgWide), "Y", Set.of(orgWide)));
        assertThat(forZ).as("加盟していない Z のメンバーには何も残らない").isEmpty();
    }

    // =========================================================================
    // AC-N08: どちらかの親組織でのロールで min_view_role を満たせる
    // =========================================================================

    @Test
    @DisplayName("AC-N08 X では MEMBER・Y では ADMIN のユーザーも、その逆のユーザーも ADMIN_ONLY の予定を閲覧できる。両方 MEMBER なら 403")
    void N08_いずれかの親組織のロールで閾値を満たせる() throws Exception {
        Long scheduleId = saveTeamSchedule(teamT, ScheduleVisibility.ORGANIZATION, MinViewRole.ADMIN_ONLY);

        Long xMemberYAdmin = insertUser("mpv-xm-ya-" + sfx + "@example.com");
        MembershipTestHelper.insertMembership(em, xMemberYAdmin, ScopeType.ORGANIZATION, orgX, RoleKind.MEMBER);
        MembershipTestHelper.insertMembership(em, xMemberYAdmin, ScopeType.ORGANIZATION, orgY, RoleKind.MEMBER);
        MembershipTestHelper.insertUserRole(em, xMemberYAdmin, "ADMIN", null, orgY);

        Long xAdminYMember = insertUser("mpv-xa-ym-" + sfx + "@example.com");
        MembershipTestHelper.insertMembership(em, xAdminYMember, ScopeType.ORGANIZATION, orgX, RoleKind.MEMBER);
        MembershipTestHelper.insertUserRole(em, xAdminYMember, "ADMIN", null, orgX);
        MembershipTestHelper.insertMembership(em, xAdminYMember, ScopeType.ORGANIZATION, orgY, RoleKind.MEMBER);

        Long bothMember = insertUser("mpv-xm-ym-" + sfx + "@example.com");
        MembershipTestHelper.insertMembership(em, bothMember, ScopeType.ORGANIZATION, orgX, RoleKind.MEMBER);
        MembershipTestHelper.insertMembership(em, bothMember, ScopeType.ORGANIZATION, orgY, RoleKind.MEMBER);
        em.flush();
        em.clear();

        // 現行は片方の親のロールしか評価しないため、Y で ADMIN / X で ADMIN のどちらかが必ず 403（決定的に red）。
        List<String> failures = new ArrayList<>();
        expectStatus(failures, "X=MEMBER・Y=ADMIN", xMemberYAdmin, teamTSlug, scheduleId, 200);
        expectStatus(failures, "X=ADMIN・Y=MEMBER", xAdminYMember, teamTSlug, scheduleId, 200);
        // 塞ぎすぎ・開けすぎの番人: どちらの親でも閾値未満なら見えない。
        expectStatus(failures, "X=MEMBER・Y=MEMBER", bothMember, teamTSlug, scheduleId, 403);
        assertThat(failures).as("いずれかの親組織でのロールによる min_view_role 評価").isEmpty();
    }

    // =========================================================================
    // AC-N09: 停止中の親組織を経由する権利は数えない
    // =========================================================================

    @Test
    @DisplayName("AC-N09 X 停止中なら Y 経由だけが生き、Y 停止中なら X 経由だけが生きる（停止中の親を経由する権利は数えない）")
    void N09_停止中の親を経由する権利は数えない() throws Exception {
        Long scheduleId = saveTeamSchedule(teamT, ScheduleVisibility.ORGANIZATION, MinViewRole.MEMBER_PLUS);
        List<String> failures = new ArrayList<>();

        // 局面1: X 停止中・Y 稼働中
        setOrganizationDeleted(orgX, true);
        expectStatus(failures, "[X停止] Y のメンバー（Y 経由）", yMemberId, teamTSlug, scheduleId, 200);
        expectStatus(failures, "[X停止] X のメンバー（停止中の X 経由のみ）", xMemberId, teamTSlug, scheduleId, 403);

        // 局面2: X 稼働中・Y 停止中（同じ行順のまま向きだけ入れ替える）
        setOrganizationDeleted(orgX, false);
        setOrganizationDeleted(orgY, true);
        expectStatus(failures, "[Y停止] X のメンバー（X 経由）", xMemberId, teamTSlug, scheduleId, 200);
        expectStatus(failures, "[Y停止] Y のメンバー（停止中の Y 経由のみ）", yMemberId, teamTSlug, scheduleId, 403);

        // 局面3: 両方停止中なら、どの経路も数えない（fail-closed）
        setOrganizationDeleted(orgX, true);
        expectStatus(failures, "[両方停止] X のメンバー", xMemberId, teamTSlug, scheduleId, 403);
        expectStatus(failures, "[両方停止] Y のメンバー", yMemberId, teamTSlug, scheduleId, 403);

        // 現行は親を1つに潰したうえで「その親が停止中なら全員不可視」とするため、
        // 局面1か局面2のどちらかで稼働中の側のメンバーが 403 になる（決定的に red）。
        assertThat(failures).as("親組織ごとの停止判定").isEmpty();
    }

    // =========================================================================
    // AC-G130: スナップショットの複数親対応と SQL 本数が一定であること
    // =========================================================================

    @Test
    @DisplayName("AC-G130 親組織5件のチームでも各親のメンバーが親組織メンバーと判定され、snapshotForUser の SQL 本数は親1件のときと同じ")
    void G130_複数親のスナップショットとSQL本数一定() {
        // 対照: 親1件のチーム
        Long orgSingle = insertOrganization("MP可視 単一親 " + sfx, "mpv-s-" + sfx);
        Long teamSingle = insertTeam("MP可視 単一親チーム " + sfx, "mpv-st-" + sfx);
        linkTeamToOrganization(teamSingle, orgSingle, LocalDateTime.of(2026, 4, 1, 9, 0));
        Long singleMember = newOrgMember("s", orgSingle);

        // 親5件のチーム
        Long team5 = insertTeam("MP可視 5親チーム " + sfx, "mpv-t5-" + sfx);
        List<Long> parents = new ArrayList<>();
        List<Long> parentMembers = new ArrayList<>();
        for (int i = 0; i < 5; i++) {
            Long org = insertOrganization("MP可視 5親-" + i + " " + sfx, "mpv-p" + i + "-" + sfx);
            linkTeamToOrganization(team5, org, LocalDateTime.of(2026, 4, 1 + i, 9, 0));
            parents.add(org);
            parentMembers.add(newOrgMember("p" + i, org));
        }
        em.flush();
        em.clear();

        ScopeKey singleKey = new ScopeKey("TEAM", teamSingle);
        Statistics stats = statisticsCleared();
        UserScopeRoleSnapshot baseline = membershipBatchQueryService.snapshotForUser(
                singleMember, Set.of(), Set.of(singleKey));
        long baselineCount = stats.getPrepareStatementCount();
        assertThat(baseline.isMemberOfParentOrg(singleKey)).isTrue();

        ScopeKey key5 = new ScopeKey("TEAM", team5);
        List<String> notMember = new ArrayList<>();
        List<String> countDrift = new ArrayList<>();
        for (int i = 0; i < parents.size(); i++) {
            em.clear();
            stats = statisticsCleared();
            UserScopeRoleSnapshot snap = membershipBatchQueryService.snapshotForUser(
                    parentMembers.get(i), Set.of(), Set.of(key5));
            long count = stats.getPrepareStatementCount();
            if (count != baselineCount) {
                countDrift.add("親" + i + ": " + count + " 本（親1件では " + baselineCount + " 本）");
            }
            if (!snap.isMemberOfParentOrg(key5)) {
                notMember.add("親" + i + "（org=" + parents.get(i) + "）のメンバー");
            }
        }

        assertThat(countDrift).as("SQL 本数は親組織の数に比例して増えない（N+1 にならない）").isEmpty();
        // 現行は 5 親のうち1件にしか潰れないため、4人が親組織メンバーと判定されない（決定的に red）。
        assertThat(notMember).as("どの親組織のメンバーも ORGANIZATION_WIDE の閲覧資格を持つ").isEmpty();
    }

    // =========================================================================
    // AC-G131（resolver 側）: 親組織が0件でも落ちずに空になる
    // =========================================================================

    @Test
    @DisplayName("AC-G131 親組織0件のチームは、ScopeAncestorResolver が空（entry 無しか空集合）を返し、スナップショット・閲覧判定も例外にならず不可視になる")
    void G131_親組織0件でも落ちずに空() throws Exception {
        ScopeKey orphan = new ScopeKey("TEAM", teamOrphan);

        Map<ScopeKey, ?> parents = scopeAncestorResolver.resolveParentOrgIds(Set.of(orphan));
        Object value = parents.get(orphan);
        assertThat(value == null || (value instanceof Collection<?> c && c.isEmpty()))
                .as("親組織0件のチームの親組織は空であるべし: " + value)
                .isTrue();

        assertThatCode(() -> {
            UserScopeRoleSnapshot snap = membershipBatchQueryService.snapshotForUser(
                    xMemberId, Set.of(), Set.of(orphan));
            assertThat(snap.isMemberOfParentOrg(orphan)).isFalse();
            assertThat(snap.isParentOrgInactive(orphan)).isFalse();
        }).doesNotThrowAnyException();

        Long scheduleId = saveTeamSchedule(teamOrphan, ScheduleVisibility.ORGANIZATION, MinViewRole.MEMBER_PLUS);
        List<String> failures = new ArrayList<>();
        expectStatus(failures, "親組織0件チームの ORGANIZATION_WIDE を無関係の組織メンバーが見る",
                xMemberId, teamOrphanSlug, scheduleId, 403);
        assertThat(failures).isEmpty();
    }

    // =========================================================================
    // ヘルパー
    // =========================================================================

    /** 期待ステータスと違えば failures に積む（X 側・Y 側を両方評価してから落とすため）。 */
    private void expectStatus(List<String> failures, String label, Long viewer,
                              String teamSlug, Long scheduleId, int expected) throws Exception {
        em.flush();
        em.clear();
        setAuthentication(viewer);
        int actual = mockMvc.perform(get("/api/v1/teams/{slug}/schedules/{id}", teamSlug, scheduleId))
                .andReturn().getResponse().getStatus();
        if (actual != expected) {
            failures.add(label + ": 期待 " + expected + " / 実際 " + actual);
        }
    }

    private Statistics statisticsCleared() {
        em.flush();
        em.clear();
        SessionFactory sf = em.getEntityManagerFactory().unwrap(SessionFactory.class);
        Statistics stats = sf.getStatistics();
        stats.setStatisticsEnabled(true);
        stats.clear();
        return stats;
    }

    private Long newOrgMember(String tag, Long orgId) {
        Long uid = insertUser("mpv-" + tag + "-" + sfx + "@example.com");
        MembershipTestHelper.insertMembership(em, uid, ScopeType.ORGANIZATION, orgId, RoleKind.MEMBER);
        return uid;
    }

    private Long saveTeamSchedule(Long ownerTeamId, ScheduleVisibility visibility, MinViewRole minViewRole) {
        Long id = scheduleRepository.save(ScheduleEntity.builder()
                .teamId(ownerTeamId)
                .title("MP可視 チーム予定 " + visibility + " " + minViewRole)
                .startAt(LocalDateTime.of(2026, 4, 10, 10, 0))
                .endAt(LocalDateTime.of(2026, 4, 10, 12, 0))
                .eventType(EventType.PRACTICE)
                .visibility(visibility)
                .minViewRole(minViewRole)
                .status(ScheduleStatus.SCHEDULED)
                .attendanceRequired(true)
                .allowProxyAttendance(true)
                .isProxyAutoAccept(false)
                .createdBy(teamAuthorId)
                .build()).getId();
        em.flush();
        em.clear();
        return id;
    }

    /** 停止中 = 論理削除（OrganizationRepository#findInactiveIdsByIdIn の現行定義）。 */
    private void setOrganizationDeleted(Long orgId, boolean deleted) {
        em.createNativeQuery("UPDATE organizations SET deleted_at = "
                        + (deleted ? "NOW()" : "NULL") + " WHERE id = :id")
                .setParameter("id", orgId)
                .executeUpdate();
        em.flush();
        em.clear();
    }

    private void setAuthentication(Long userId) {
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(userId.toString(), null, List.of()));
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
                                + "VALUES (:email, 'MPV', 'テスト', 'MPV テスト', 'ACTIVE', "
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

    /** 書き込み API が未実装のため、ACTIVE な加盟行を直接 INSERT する（responded_at = 成立時刻）。 */
    private void linkTeamToOrganization(Long teamId, Long orgId, LocalDateTime respondedAt) {
        em.createNativeQuery(
                        "INSERT INTO team_org_memberships ("
                                + "team_id, organization_id, status, invited_at, responded_at, created_at) "
                                + "VALUES (:tid, :oid, 'ACTIVE', :invited, :responded, NOW())")
                .setParameter("tid", teamId)
                .setParameter("oid", orgId)
                .setParameter("invited", respondedAt.minusDays(1))
                .setParameter("responded", respondedAt)
                .executeUpdate();
    }
}
