package com.mannschaft.app.social.announcement.audience;

import com.mannschaft.app.membership.domain.RoleKind;
import com.mannschaft.app.membership.domain.ScopeType;
import com.mannschaft.app.notification.fanout.NotificationFanoutJob;
import com.mannschaft.app.notification.fanout.NotificationFanoutJobRepository;
import com.mannschaft.app.notification.fanout.NotificationFanoutJobStatus;
import com.mannschaft.app.notification.fanout.NotificationFanoutWorker;
import com.mannschaft.app.organization.entity.OrganizationEntity;
import com.mannschaft.app.organization.teamgroup.entity.OrgTeamGroupEntity;
import com.mannschaft.app.social.announcement.AnnouncementBroadcastService;
import com.mannschaft.app.social.announcement.AnnouncementChannel;
import com.mannschaft.app.social.announcement.AnnouncementContentRequest;
import com.mannschaft.app.social.announcement.BroadcastRequest;
import com.mannschaft.app.support.test.MembershipTestHelper;
import com.mannschaft.app.team.entity.TeamEntity;
import com.mannschaft.app.team.entity.TeamOrgMembershipEntity;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * F01.2.1 部隊 6-E 試練 — グループ宛て・チーム宛てアンケート告知の push（設計書 §8.5）。
 *
 * <p>実 MySQL＋実 Security フィルタ＋ MockMvc で、認可・Service・Repository・Worker はモックしない。
 * ジョブ表・宛先集合の表・通知の表を SELECT して検証する。fan-out ジョブは Worker が独立コミット
 * （REQUIRES_NEW）で読むため、本クラスはテスト全体を {@code @Transactional} にせず、
 * フィクスチャを都度コミットする（テストごとに組織・人物を新規に作り、他テストと干渉しない）。</p>
 *
 * <h2>AC ↔ テスト対応</h2>
 * <ul>
 *   <li>H04（push 側）→ {@link #H04_送信後に宛先グループへ移ったチームにはpushが届かない()}</li>
 *   <li>H07 → {@link #H07_グループ宛てアンケートはORGANIZATION_TEAMSジョブ1件だけが作られる()}</li>
 *   <li>H08 → {@link #H08_宛先チームのメンバーと直属メンバーにpushが届き宛先外には届かない()}</li>
 *   <li>H09 → {@link #H09_複数の宛先グループに属するユーザーにもpushは1件だけ()}</li>
 *   <li>H10 → {@link #H10_enqueue後に離脱したチームのメンバーには届かない()}</li>
 *   <li>H11 → {@link #H11_すべてのチーム宛てでは従来どおりORGANIZATIONジョブが1件作られる()}</li>
 *   <li>H13 → {@link #H13_MEMBERがグループ宛てで送るとpushは出ずジョブも作られない()}</li>
 *   <li>H18 → {@link #H18_チームを選ぶでもpushは選んだチームと直属メンバーだけに届く()}</li>
 *   <li>H19 → {@link #H19_掲示板の告知ではfanoutジョブが作られない()}</li>
 *   <li>H21 → {@link #H21_コンテンツ作成がロールバックされたらジョブも宛先集合も作られない()}</li>
 *   <li>H22 → {@link #H22_MEMBERS_AND_ABOVEでは純SUPPORTERにpushが届かない()} ／
 *       {@link #H22_SUPPORTERS_AND_ABOVEでは純SUPPORTERにもpushが届く()}</li>
 *   <li>H27 → {@link #H27_MEMBERがチームを選ぶで送るとpushは出ずジョブも作られない()}</li>
 *   <li>H28 → {@link #H28_回帰_すべてのチーム宛てはORGANIZATIONジョブ1件()} ／
 *       {@link #H28_回帰_チームを選ぶの掲示板告知はジョブなし()}</li>
 *   <li>H30 → {@link #H30_pushを出せる送信者は組織ADMINとMANAGE_CONTENT付きDEPUTYとSYSTEM_ADMINだけ(String)} ／
 *       {@link #H30_MANAGE_CONTENTの無いDEPUTYではpushは出ずジョブも作られない()}</li>
 *   <li>H31 → {@link #H31_同じ組織で並行する2ジョブの宛先はscope_refごとに分かれ混ざらない()}</li>
 *   <li>H33 → {@link #H33_受信ユーザーはWorker処理時の所属で決まり対象チームは送信時に固定される()}</li>
 * </ul>
 * H32a・H32b・H32c は別クラス（{@code OrgTeamsShardScopeRefIT}・{@code OrgTeamsFanoutRecipientSourceIT}）。
 *
 * <p>「ジョブが作られない」系の検証は、リスナー（AFTER_COMMIT・非同期）が遅れて enqueue する経路も
 * 捕まえるため、送信後に数秒待ってから表を SELECT する。</p>
 */
@AutoConfigureMockMvc
@EnabledIf("com.mannschaft.app.support.test.AbstractMySqlIntegrationTest#isDockerAvailable")
@DisplayName("F01.2.1 6-E グループ宛て告知の push（AC-H04・H07〜H11・H13・H18・H19・H21・H22・H27・H28・H30・H31・H33）")
class BroadcastGroupPushIT extends AbstractBroadcastAudienceIT {

    private static final String TEAMS_SCOPE = "ORGANIZATION_TEAMS";
    private static final String ORG_SCOPE = "ORGANIZATION";
    private static final String SURVEY_TYPE = "SURVEY_CREATED";
    private static final long SETTLE_MILLIS = 2_500L;

    private static final AtomicLong USER_SEQ = new AtomicLong(947_000_000L);

    @Autowired
    private JdbcTemplate jdbc;
    @Autowired
    private PlatformTransactionManager transactionManager;
    @Autowired
    private NotificationFanoutWorker worker;
    @Autowired
    private NotificationFanoutJobRepository jobRepository;
    @Autowired
    private BroadcastAudienceResolver audienceResolver;
    @Autowired
    private AnnouncementBroadcastService broadcastService;

    // =====================================================================
    // フィクスチャ
    // =====================================================================

    /** 1シナリオぶんの組織・グループ・チーム・人物。 */
    private static final class Sc {
        OrganizationEntity org;
        OrganizationEntity orgY;
        OrgTeamGroupEntity g1;
        OrgTeamGroupEntity g2;
        OrgTeamGroupEntity g3;
        OrgTeamGroupEntity g9;
        TeamEntity t0;
        TeamEntity t1;
        TeamEntity t2;
        TeamEntity t3;
        TeamEntity t4;
        /** 組織 ADMIN（送信者）。 */
        long xa;
        /** MANAGE_CONTENT を付与された DEPUTY_ADMIN。 */
        long xd;
        /** MANAGE_CONTENT を持たない DEPUTY_ADMIN。 */
        long xd2;
        /** 組織 MEMBER。 */
        long xm;
        /** 組織の直属メンバー。 */
        long xo;
        /** SYSTEM_ADMIN（組織にも MEMBER として在籍）。 */
        long sa;
        long t1m;
        long t2m;
        long t3m;
        long t0m;
        long t4m;
        /** T1 と T2 の両方に属する。 */
        long t12m;
        /** T1 のメンバーで、組織の直属メンバーでもある。 */
        long t1xo;
        /** どの組織にも属さない。 */
        long outsider;
        /** 組織 Y の直属メンバー。 */
        long ym;
        /** 組織の純 SUPPORTER。 */
        long orgSupporter;
        /** T1 の純 SUPPORTER。 */
        long teamSupporter;
    }

    private Sc scenario() {
        TransactionTemplate tx = new TransactionTemplate(transactionManager);
        return tx.execute(status -> {
            Sc sc = new Sc();
            sc.org = org();
            sc.orgY = org();
            sc.g1 = newGroup(sc.org.getId(), "G1", 0);
            sc.g2 = newGroup(sc.org.getId(), "G2", 1);
            sc.g3 = newGroup(sc.org.getId(), "G3", 2);
            sc.g9 = newGroup(sc.org.getId(), "G9", 3);
            sc.t0 = team("T0");
            sc.t1 = team("T1");
            sc.t2 = team("T2");
            sc.t3 = team("T3");
            sc.t4 = team("T4");
            affiliate(sc.t0.getId(), sc.org.getId(), TeamOrgMembershipEntity.Status.ACTIVE, null);
            affiliate(sc.t1.getId(), sc.org.getId(), TeamOrgMembershipEntity.Status.ACTIVE, sc.g1.getId());
            affiliate(sc.t2.getId(), sc.org.getId(), TeamOrgMembershipEntity.Status.ACTIVE, sc.g2.getId());
            affiliate(sc.t3.getId(), sc.org.getId(), TeamOrgMembershipEntity.Status.ACTIVE, sc.g3.getId());
            affiliate(sc.t4.getId(), sc.org.getId(), TeamOrgMembershipEntity.Status.ACTIVE, sc.g9.getId());

            sc.xa = nextUser();
            sc.xd = nextUser();
            sc.xd2 = nextUser();
            sc.xm = nextUser();
            sc.xo = nextUser();
            sc.sa = nextUser();
            seedOrgPerson(sc.xa, sc.org.getId(), "ADMIN");
            seedOrgPerson(sc.xd, sc.org.getId(), "DEPUTY_ADMIN");
            seedOrgPerson(sc.xd2, sc.org.getId(), "DEPUTY_ADMIN");
            seedOrgPerson(sc.xm, sc.org.getId(), "MEMBER");
            seedOrgPerson(sc.xo, sc.org.getId(), "MEMBER");
            seedOrgPerson(sc.sa, sc.org.getId(), "MEMBER");
            MembershipTestHelper.insertUserRole(em, sc.sa, "SYSTEM_ADMIN", null, null);
            grantOrgPermission(sc.xd, sc.org.getId(), "MANAGE_CONTENT");

            sc.t1m = teamMember(sc.t1);
            sc.t2m = teamMember(sc.t2);
            sc.t3m = teamMember(sc.t3);
            sc.t0m = teamMember(sc.t0);
            sc.t4m = teamMember(sc.t4);
            sc.t12m = teamMember(sc.t1);
            MembershipTestHelper.insertMembership(em, sc.t12m, ScopeType.TEAM, sc.t2.getId(), RoleKind.MEMBER);
            sc.t1xo = teamMember(sc.t1);
            MembershipTestHelper.insertMembership(
                    em, sc.t1xo, ScopeType.ORGANIZATION, sc.org.getId(), RoleKind.MEMBER);
            sc.outsider = nextUser();
            seedUserOnly(sc.outsider);
            sc.ym = nextUser();
            seedOrgPerson(sc.ym, sc.orgY.getId(), "MEMBER");

            sc.orgSupporter = nextUser();
            seedUserOnly(sc.orgSupporter);
            MembershipTestHelper.insertMembership(
                    em, sc.orgSupporter, ScopeType.ORGANIZATION, sc.org.getId(), RoleKind.SUPPORTER);
            sc.teamSupporter = nextUser();
            seedUserOnly(sc.teamSupporter);
            MembershipTestHelper.insertMembership(
                    em, sc.teamSupporter, ScopeType.TEAM, sc.t1.getId(), RoleKind.SUPPORTER);
            em.flush();
            return sc;
        });
    }

    private OrganizationEntity org() {
        return organizationRepository.saveAndFlush(OrganizationEntity.builder()
                .name("6E試練組織" + System.nanoTime())
                .slug("bc6e-" + System.nanoTime() % 1_000_000_000L + "-" + USER_SEQ.incrementAndGet())
                .orgType(OrganizationEntity.OrgType.OTHER)
                .visibility(OrganizationEntity.Visibility.PUBLIC)
                .hierarchyVisibility(OrganizationEntity.HierarchyVisibility.NONE)
                .supporterEnabled(true)
                .teamGroupsEnabled(true)
                .build());
    }

    private TeamEntity team(String name) {
        return teamRepository.saveAndFlush(TeamEntity.builder()
                .slug("bc6e-t-" + System.nanoTime() % 1_000_000_000L + "-" + USER_SEQ.incrementAndGet())
                .name(name)
                .visibility(TeamEntity.Visibility.PUBLIC)
                .supporterEnabled(true)
                .build());
    }

    private static long nextUser() {
        return USER_SEQ.incrementAndGet();
    }

    private long teamMember(TeamEntity team) {
        long id = nextUser();
        seedTeamPerson(id, team.getId(), "MEMBER");
        return id;
    }

    /** DEPUTY_ADMIN に組織スコープの権限を権限グループ経由で付与する（判定は user_permission_groups 経路）。 */
    private void grantOrgPermission(long userId, long orgId, String permissionName) {
        jdbc.update("INSERT IGNORE INTO permissions (name, display_name, scope, created_at, updated_at) "
                + "VALUES (?, ?, 'ORGANIZATION', NOW(), NOW())", permissionName, permissionName);
        long permissionId = jdbc.queryForObject(
                "SELECT id FROM permissions WHERE name = ?", Long.class, permissionName);
        String groupName = "6E-grant-" + UUID.randomUUID();
        jdbc.update("INSERT INTO permission_groups (team_id, organization_id, target_role, name, created_by, "
                + "deleted_at, created_at, updated_at) VALUES (NULL, ?, 'DEPUTY_ADMIN', ?, NULL, NULL, NOW(), NOW())",
                orgId, groupName);
        long groupId = jdbc.queryForObject("SELECT id FROM permission_groups WHERE name = ?", Long.class, groupName);
        jdbc.update("INSERT INTO permission_group_permissions (group_id, permission_id, created_at) VALUES (?, ?, NOW())",
                groupId, permissionId);
        jdbc.update("INSERT INTO user_permission_groups (user_id, group_id, assigned_by, created_at) "
                + "VALUES (?, ?, NULL, NOW())", userId, groupId);
    }

    // =====================================================================
    // 送信・観測
    // =====================================================================

    private long sendSurvey(long actor, Sc sc, Map<String, Object> audience) throws Exception {
        ResultActions result = broadcastToOrg(actor, sc.org.getId(), body("SURVEY", audience));
        result.andExpect(status().isCreated());
        return feedIdOf(result);
    }

    private static Map<String, Object> groupRange(Sc sc) {
        return Map.of("targetGroupRange", range(null, sc.g2.getId()));
    }

    /** 決定的キー（設計書 §8.5.3）: 冪等キー {@code source_event_uuid}。 */
    private static UUID broadcastKey(long feedId) {
        return UUID.nameUUIDFromBytes(("F02.8:broadcast:" + feedId).getBytes(StandardCharsets.UTF_8));
    }

    /** 決定的キー（設計書 §5.6）: 宛先集合の {@code audience_snapshot_id}。 */
    private static UUID audienceId(long feedId) {
        return UUID.nameUUIDFromBytes(("F02.8:broadcast-audience:" + feedId).getBytes(StandardCharsets.UTF_8));
    }

    private static String hex(UUID id) {
        return id.toString().replace("-", "").toUpperCase();
    }

    /** 送信後に数秒待ってから、組織に紐づく fan-out ジョブを全件返す（遅れて enqueue する経路も捕まえる）。 */
    private List<Map<String, Object>> jobsAfterSettling(long orgId) throws InterruptedException {
        Thread.sleep(SETTLE_MILLIS);
        return jobs(orgId);
    }

    private List<Map<String, Object>> jobs(long orgId) {
        return jdbc.queryForList(
                "SELECT scope_type, scope_ref, notification_type, shard_index, shard_count, include_supporters, "
                        + "HEX(source_event_uuid) AS ev FROM notification_fanout_jobs "
                        + "WHERE organization_id = ? ORDER BY scope_type, shard_index", orgId);
    }

    private List<Map<String, Object>> jobsOfType(List<Map<String, Object>> all, String scopeType) {
        return all.stream().filter(j -> scopeType.equals(j.get("scope_type"))).toList();
    }

    /** ジョブが {@code count} 件に達するまで最大10秒待つ（リスナー経由の非同期 enqueue 用）。 */
    private List<Map<String, Object>> awaitJobs(long orgId, String scopeType, int count) throws InterruptedException {
        long deadline = System.currentTimeMillis() + 10_000L;
        List<Map<String, Object>> found = jobsOfType(jobs(orgId), scopeType);
        while (found.size() < count && System.currentTimeMillis() < deadline) {
            Thread.sleep(200L);
            found = jobsOfType(jobs(orgId), scopeType);
        }
        return found;
    }

    private long audienceHeaderCount(long orgId) {
        Long n = jdbc.queryForObject(
                "SELECT COUNT(*) FROM notification_fanout_audiences WHERE organization_id = ?", Long.class, orgId);
        return n == null ? 0L : n;
    }

    private Set<Long> audienceTeamIds(UUID audienceId) {
        return jdbc.queryForList(
                        "SELECT team_id FROM notification_fanout_audience_teams "
                                + "WHERE audience_snapshot_id = UNHEX(?)", Long.class, hex(audienceId))
                .stream().collect(Collectors.toSet());
    }

    private List<NotificationFanoutJob> teamsJobEntities(long feedId) {
        return jobRepository.findByScopeTypeAndScopeRefAndNotificationTypeAndSourceEventUuidOrderByShardIndexAsc(
                TEAMS_SCOPE, audienceId(feedId).toString(), SURVEY_TYPE, broadcastKey(feedId));
    }

    /** ORGANIZATION_TEAMS ジョブを Worker に配信させる（分割された子シャードも続けて処理する）。 */
    private void runWorker(long feedId) {
        for (NotificationFanoutJob job : teamsJobEntities(feedId)) {
            worker.processOne(job);
        }
        for (NotificationFanoutJob job : teamsJobEntities(feedId)) {
            if (job.getStatus() == NotificationFanoutJobStatus.PENDING) {
                worker.processOne(job);
            }
        }
        for (NotificationFanoutJob job : teamsJobEntities(feedId)) {
            assertThat(job.getStatus()).as("ジョブは完了している shardIndex=" + job.getShardIndex())
                    .isEqualTo(NotificationFanoutJobStatus.DONE);
        }
    }

    /** ユーザーごとの SURVEY_CREATED 通知の件数（0件のユーザーは含まれない）。 */
    private Map<Long, Integer> notified(Collection<Long> userIds) {
        String placeholders = userIds.stream().map(u -> "?").collect(Collectors.joining(","));
        List<Object> args = new ArrayList<>();
        args.add(SURVEY_TYPE);
        args.addAll(userIds);
        Map<Long, Integer> counts = new HashMap<>();
        jdbc.query("SELECT user_id, COUNT(*) AS c FROM notifications WHERE notification_type = ? "
                        + "AND user_id IN (" + placeholders + ") GROUP BY user_id",
                rs -> {
                    counts.put(rs.getLong("user_id"), rs.getInt("c"));
                }, args.toArray());
        return counts;
    }

    private Map<Long, Integer> notifiedAll(Sc sc) {
        List<Long> all = List.of(sc.xa, sc.xd, sc.xd2, sc.xm, sc.xo, sc.sa, sc.t1m, sc.t2m, sc.t3m, sc.t0m,
                sc.t4m, sc.t12m, sc.t1xo, sc.outsider, sc.ym, sc.orgSupporter, sc.teamSupporter);
        return notified(all);
    }

    // =====================================================================
    // H07 / H08 / H09
    // =====================================================================

    @Test
    @DisplayName("H07: グループ宛てのアンケートでは ORGANIZATION ジョブが作られず、ORGANIZATION_TEAMS ジョブが1件だけ作られる（冪等キー・宛先集合）")
    void H07_グループ宛てアンケートはORGANIZATION_TEAMSジョブ1件だけが作られる() throws Exception {
        Sc sc = scenario();

        long feedId = sendSurvey(sc.xa, sc, groupRange(sc));

        List<Map<String, Object>> all = jobsAfterSettling(sc.org.getId());
        assertThat(jobsOfType(all, ORG_SCOPE)).as("従来の ORGANIZATION ジョブは作られない").isEmpty();
        List<Map<String, Object>> teamsJobs = jobsOfType(all, TEAMS_SCOPE);
        assertThat(teamsJobs).as("ORGANIZATION_TEAMS ジョブがちょうど1件").hasSize(1);
        Map<String, Object> job = teamsJobs.get(0);
        assertThat(job.get("scope_ref")).as("scope_ref は audience_snapshot_id の UUID 文字列")
                .isEqualTo(audienceId(feedId).toString());
        assertThat(job.get("ev")).as("冪等キーはフィード ID から導いた決定的な UUID")
                .isEqualTo(hex(broadcastKey(feedId)));
        assertThat(job.get("notification_type")).isEqualTo(SURVEY_TYPE);
        assertThat(((Number) job.get("shard_count")).intValue())
                .as("enqueue は AUTO（shard_count=0）。評価は Worker に任せる").isZero();

        assertThat(audienceHeaderCount(sc.org.getId())).as("宛先集合の見出しが1件").isEqualTo(1L);
        assertThat(audienceTeamIds(audienceId(feedId))).as("宛先チームは G1・G2 の T1・T2")
                .containsExactlyInAnyOrder(sc.t1.getId(), sc.t2.getId());
    }

    @Test
    @DisplayName("H08: T1・T2 のメンバーと直属メンバーに push が1件ずつ届き、宛先外（T3・T0・他組織・組織外）には0件")
    void H08_宛先チームのメンバーと直属メンバーにpushが届き宛先外には届かない() throws Exception {
        Sc sc = scenario();
        long feedId = sendSurvey(sc.xa, sc, groupRange(sc));
        jobsAfterSettling(sc.org.getId());

        runWorker(feedId);

        Map<Long, Integer> counts = notifiedAll(sc);
        for (long expected : List.of(sc.t1m, sc.t2m, sc.t12m, sc.t1xo, sc.xo, sc.xm, sc.xd, sc.xd2, sc.sa)) {
            assertThat(counts.get(expected)).as("宛先に届く userId=" + expected).isEqualTo(1);
        }
        for (long excluded : List.of(sc.t3m, sc.t0m, sc.t4m, sc.outsider, sc.ym)) {
            assertThat(counts).as("宛先外には届かない userId=" + excluded).doesNotContainKey(excluded);
        }
    }

    @Test
    @DisplayName("H09: 2グループ（T1・T2）に属するユーザーにも、T1 と組織直属を兼ねるユーザーにも push は1件だけ")
    void H09_複数の宛先グループに属するユーザーにもpushは1件だけ() throws Exception {
        Sc sc = scenario();
        long feedId = sendSurvey(sc.xa, sc, groupRange(sc));
        jobsAfterSettling(sc.org.getId());

        runWorker(feedId);

        Map<Long, Integer> counts = notifiedAll(sc);
        assertThat(counts.get(sc.t12m)).as("T1 と T2 の両方に属するユーザー").isEqualTo(1);
        assertThat(counts.get(sc.t1xo)).as("T1 のメンバーかつ組織の直属メンバー").isEqualTo(1);
    }

    // =====================================================================
    // H04 / H10 / H33
    // =====================================================================

    @Test
    @DisplayName("H04: 送信後に宛先グループ(G1)へ移ったチーム(T4)のメンバーには push が届かない（宛先チームは送信時に固定）")
    void H04_送信後に宛先グループへ移ったチームにはpushが届かない() throws Exception {
        Sc sc = scenario();
        long feedId = sendSurvey(sc.xa, sc, Map.of("targetGroupIds", ids(sc.g1.getId())));
        jobsAfterSettling(sc.org.getId());
        assertThat(audienceTeamIds(audienceId(feedId))).containsExactly(sc.t1.getId());

        moveTeamToGroup(sc.t4.getId(), sc.org.getId(), sc.g1.getId());
        runWorker(feedId);

        Map<Long, Integer> counts = notifiedAll(sc);
        assertThat(counts.get(sc.t1m)).as("送信時点の宛先 T1 には届く").isEqualTo(1);
        assertThat(counts).as("送信後に G1 へ移った T4 のメンバーには届かない").doesNotContainKey(sc.t4m);
        assertThat(audienceTeamIds(audienceId(feedId))).as("宛先チームの集合は変わらない")
                .containsExactly(sc.t1.getId());
    }

    @Test
    @DisplayName("H10: enqueue の後で組織から離脱した（加盟が無くなった）チームのメンバーには届かない")
    void H10_enqueue後に離脱したチームのメンバーには届かない() throws Exception {
        Sc sc = scenario();
        long feedId = sendSurvey(sc.xa, sc, groupRange(sc));
        jobsAfterSettling(sc.org.getId());

        jdbc.update("DELETE FROM team_org_memberships WHERE team_id = ? AND organization_id = ?",
                sc.t2.getId(), sc.org.getId());
        runWorker(feedId);

        Map<Long, Integer> counts = notifiedAll(sc);
        assertThat(counts.get(sc.t1m)).as("加盟を続けている T1 には届く").isEqualTo(1);
        assertThat(counts).as("離脱した T2 のメンバーには届かない").doesNotContainKey(sc.t2m);
        assertThat(audienceTeamIds(audienceId(feedId))).as("宛先集合の行そのものは送信時のまま")
                .containsExactlyInAnyOrder(sc.t1.getId(), sc.t2.getId());
    }

    @Test
    @DisplayName("H33: 受信ユーザーは Worker 処理時の所属で決まる（enqueue 後の参加者に届き、離脱者に届かない）。対象チームは送信時に固定")
    void H33_受信ユーザーはWorker処理時の所属で決まり対象チームは送信時に固定される() throws Exception {
        Sc sc = scenario();
        long feedId = sendSurvey(sc.xa, sc, groupRange(sc));
        jobsAfterSettling(sc.org.getId());

        long lateJoiner = nextUser();
        new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
            seedUserOnly(lateJoiner);
            MembershipTestHelper.insertMembership(em, lateJoiner, ScopeType.TEAM, sc.t1.getId(), RoleKind.MEMBER);
        });
        jdbc.update("UPDATE memberships SET left_at = DATE_ADD(joined_at, INTERVAL 1 SECOND), leave_reason = 'SELF' "
                + "WHERE user_id = ? AND scope_type = 'TEAM' AND scope_id = ?", sc.t1m, sc.t1.getId());
        moveTeamToGroup(sc.t4.getId(), sc.org.getId(), sc.g2.getId());

        runWorker(feedId);

        Map<Long, Integer> counts = notified(List.of(lateJoiner, sc.t1m, sc.t2m, sc.t4m));
        assertThat(counts.get(lateJoiner)).as("enqueue 後に T1 へ参加した人には届く").isEqualTo(1);
        assertThat(counts).as("enqueue 後に T1 を離れた人には届かない").doesNotContainKey(sc.t1m);
        assertThat(counts.get(sc.t2m)).isEqualTo(1);
        assertThat(counts).as("送信後に宛先グループへ移った T4 は対象外（対象チームは送信時に固定）")
                .doesNotContainKey(sc.t4m);
    }

    private void moveTeamToGroup(long teamId, long orgId, UUID groupId) {
        jdbc.update("UPDATE team_org_memberships SET group_id = UNHEX(REPLACE(?, '-', '')) "
                + "WHERE team_id = ? AND organization_id = ?", groupId.toString(), teamId, orgId);
    }

    // =====================================================================
    // H11 / H28（回帰）
    // =====================================================================

    @Test
    @DisplayName("H11: 「すべてのチーム」では従来どおり ORGANIZATION のジョブが1件作られ、ORGANIZATION_TEAMS は作られない")
    void H11_すべてのチーム宛てでは従来どおりORGANIZATIONジョブが1件作られる() throws Exception {
        Sc sc = scenario();

        sendSurvey(sc.xa, sc, Map.of());

        List<Map<String, Object>> orgJobs = awaitJobs(sc.org.getId(), ORG_SCOPE, 1);
        assertThat(orgJobs).as("リスナーが ORGANIZATION fan-out を1件").hasSize(1);
        assertThat(orgJobs.get(0).get("scope_ref")).isEqualTo(String.valueOf(sc.org.getId()));
        assertThat(jobsOfType(jobsAfterSettling(sc.org.getId()), TEAMS_SCOPE)).isEmpty();
        assertThat(audienceHeaderCount(sc.org.getId())).as("宛先集合は作らない").isZero();
    }

    @Test
    @DisplayName("H28(回帰): 「すべてのチーム」は ORGANIZATION のジョブ1件")
    void H28_回帰_すべてのチーム宛てはORGANIZATIONジョブ1件() throws Exception {
        Sc sc = scenario();

        sendSurvey(sc.xa, sc, Map.of());

        assertThat(awaitJobs(sc.org.getId(), ORG_SCOPE, 1)).hasSize(1);
        assertThat(jobsAfterSettling(sc.org.getId())).as("他のジョブは作られない").hasSize(1);
    }

    @Test
    @DisplayName("H28(回帰): 「チームを選ぶ」の掲示板告知は fan-out ジョブも宛先集合も作らない")
    void H28_回帰_チームを選ぶの掲示板告知はジョブなし() throws Exception {
        Sc sc = scenario();

        broadcastToOrg(sc.xa, sc.org.getId(),
                bulletinBody(Map.of("targetTeamIds", List.of(sc.t1.getId(), sc.t2.getId()))))
                .andExpect(status().isCreated());

        assertThat(jobsAfterSettling(sc.org.getId())).isEmpty();
        assertThat(audienceHeaderCount(sc.org.getId())).isZero();
    }

    // =====================================================================
    // H13 / H27 / H30
    // =====================================================================

    @Test
    @DisplayName("H13: MEMBER がグループ宛てで送ると表示は絞られ（フィードは作られ）、push は出ず、ジョブも宛先集合も作られない")
    void H13_MEMBERがグループ宛てで送るとpushは出ずジョブも作られない() throws Exception {
        Sc sc = scenario();

        long feedId = sendSurvey(sc.xm, sc, groupRange(sc));

        assertThat(savedTargetGroupIds(feedId)).as("表示の絞り込みは保存される").hasSize(2);
        assertThat(jobsAfterSettling(sc.org.getId()))
                .as("ORGANIZATION も ORGANIZATION_TEAMS もジョブは作られない").isEmpty();
        assertThat(audienceHeaderCount(sc.org.getId())).isZero();
    }

    @Test
    @DisplayName("H27: MEMBER が「チームを選ぶ」で送ると push なし、ジョブも宛先集合も作られない")
    void H27_MEMBERがチームを選ぶで送るとpushは出ずジョブも作られない() throws Exception {
        Sc sc = scenario();

        long feedId = sendSurvey(sc.xm, sc, Map.of("targetTeamIds", List.of(sc.t1.getId(), sc.t2.getId())));

        assertThat(savedTargetTeamIds(feedId)).as("表示の絞り込みは保存される")
                .containsExactlyInAnyOrder(sc.t1.getId(), sc.t2.getId());
        assertThat(jobsAfterSettling(sc.org.getId())).isEmpty();
        assertThat(audienceHeaderCount(sc.org.getId())).isZero();
    }

    @ParameterizedTest(name = "H30: 送信者 {0} は push を出せる")
    @ValueSource(strings = {"XA", "XD", "SA"})
    @DisplayName("H30: push を出せるのは組織 ADMIN・MANAGE_CONTENT を持つ DEPUTY_ADMIN・SYSTEM_ADMIN だけ")
    void H30_pushを出せる送信者は組織ADMINとMANAGE_CONTENT付きDEPUTYとSYSTEM_ADMINだけ(String sender) throws Exception {
        Sc sc = scenario();
        long actor = switch (sender) {
            case "XA" -> sc.xa;
            case "XD" -> sc.xd;
            default -> sc.sa;
        };

        sendSurvey(actor, sc, groupRange(sc));

        List<Map<String, Object>> all = jobsAfterSettling(sc.org.getId());
        assertThat(jobsOfType(all, TEAMS_SCOPE)).as(sender + " は ORGANIZATION_TEAMS ジョブを1件出せる").hasSize(1);
        assertThat(jobsOfType(all, ORG_SCOPE)).isEmpty();
    }

    @Test
    @DisplayName("H30: MANAGE_CONTENT を持たない DEPUTY_ADMIN (XD2) では push は出ず、ジョブも宛先集合も作られない")
    void H30_MANAGE_CONTENTの無いDEPUTYではpushは出ずジョブも作られない() throws Exception {
        Sc sc = scenario();

        sendSurvey(sc.xd2, sc, groupRange(sc));

        assertThat(jobsAfterSettling(sc.org.getId())).isEmpty();
        assertThat(audienceHeaderCount(sc.org.getId())).isZero();
    }

    // =====================================================================
    // H18 / H19
    // =====================================================================

    @Test
    @DisplayName("H18: 「チームを選ぶ」でも push は選んだチーム(T1・T2)と直属メンバーだけに届く。宛先集合の中身は選んだチーム ID")
    void H18_チームを選ぶでもpushは選んだチームと直属メンバーだけに届く() throws Exception {
        Sc sc = scenario();
        long feedId = sendSurvey(sc.xa, sc, Map.of("targetTeamIds", List.of(sc.t1.getId(), sc.t2.getId())));

        List<Map<String, Object>> all = jobsAfterSettling(sc.org.getId());
        assertThat(jobsOfType(all, ORG_SCOPE)).as("リスナーの ORGANIZATION fan-out は出さない").isEmpty();
        assertThat(jobsOfType(all, TEAMS_SCOPE)).hasSize(1);
        assertThat(audienceTeamIds(audienceId(feedId))).containsExactlyInAnyOrder(sc.t1.getId(), sc.t2.getId());

        runWorker(feedId);

        Map<Long, Integer> counts = notifiedAll(sc);
        for (long expected : List.of(sc.t1m, sc.t2m, sc.t12m, sc.xo, sc.xm)) {
            assertThat(counts.get(expected)).isEqualTo(1);
        }
        for (long excluded : List.of(sc.t3m, sc.t0m, sc.t4m, sc.outsider, sc.ym)) {
            assertThat(counts).doesNotContainKey(excluded);
        }
    }

    @Test
    @DisplayName("H19: 掲示板の告知（グループ宛て）では fan-out ジョブも宛先集合も作られない")
    void H19_掲示板の告知ではfanoutジョブが作られない() throws Exception {
        Sc sc = scenario();

        broadcastToOrg(sc.xa, sc.org.getId(), bulletinBody(Map.of("targetGroupIds", ids(sc.g1.getId()))))
                .andExpect(status().isCreated());

        assertThat(jobsAfterSettling(sc.org.getId())).isEmpty();
        assertThat(audienceHeaderCount(sc.org.getId())).isZero();
    }

    // =====================================================================
    // H21
    // =====================================================================

    @Test
    @DisplayName("H21: コンテンツ作成がロールバックされたら、ジョブも宛先集合も作られない（告知と同一トランザクションで enqueue）")
    void H21_コンテンツ作成がロールバックされたらジョブも宛先集合も作られない() throws Exception {
        Sc sc = scenario();
        // 対照: 普通に送ればジョブと宛先集合が1組できる（これが無いと「作られない」が空振りでも通る）
        sendSurvey(sc.xa, sc, groupRange(sc));
        assertThat(jobsOfType(jobsAfterSettling(sc.org.getId()), TEAMS_SCOPE)).hasSize(1);
        long feedsBefore = feedCount(sc.org.getId());

        BroadcastAudienceSpec spec = new BroadcastAudienceSpec(
                null, List.of(sc.g3.getId()), null, null, null, "MEMBERS_AND_ABOVE");
        ResolvedBroadcastAudience resolved =
                audienceResolver.resolveForBroadcast(
                        sc.xa, "ORGANIZATION", sc.org.getId(), spec, AnnouncementChannel.SURVEY);
        BroadcastRequest request = BroadcastRequest.builder()
                .channel(AnnouncementChannel.SURVEY)
                .targetRole("MEMBERS_AND_ABOVE")
                .priority("NORMAL")
                .content(AnnouncementContentRequest.builder().title("巻き戻す告知").description("説明").build())
                .callerUserId(sc.xa)
                .scopeType("ORGANIZATION")
                .scopeId(sc.org.getId())
                .audience(resolved)
                .build();
        new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
            broadcastService.broadcast(request);
            status.setRollbackOnly();
        });

        assertThat(feedCount(sc.org.getId())).as("フィードは巻き戻る").isEqualTo(feedsBefore);
        assertThat(jobsOfType(jobsAfterSettling(sc.org.getId()), TEAMS_SCOPE))
                .as("ジョブも巻き戻る（最初の1件だけが残る）").hasSize(1);
        assertThat(audienceHeaderCount(sc.org.getId())).as("宛先集合の見出しも巻き戻る").isEqualTo(1L);
    }

    // =====================================================================
    // H22
    // =====================================================================

    @Test
    @DisplayName("H22: MEMBERS_AND_ABOVE では純 SUPPORTER（組織・チーム）に push が届かず、ジョブの include_supporters も false")
    void H22_MEMBERS_AND_ABOVEでは純SUPPORTERにpushが届かない() throws Exception {
        Sc sc = scenario();
        Map<String, Object> audience = new LinkedHashMap<>(groupRange(sc));
        audience.put("targetRole", "MEMBERS_AND_ABOVE");
        long feedId = sendSurvey(sc.xa, sc, audience);

        List<Map<String, Object>> teamsJobs = jobsOfType(jobsAfterSettling(sc.org.getId()), TEAMS_SCOPE);
        assertThat(teamsJobs).hasSize(1);
        assertThat(isTrue(teamsJobs.get(0).get("include_supporters"))).isFalse();
        runWorker(feedId);

        Map<Long, Integer> counts = notifiedAll(sc);
        assertThat(counts.get(sc.t1m)).isEqualTo(1);
        assertThat(counts).doesNotContainKeys(sc.orgSupporter, sc.teamSupporter);
    }

    @Test
    @DisplayName("H22: SUPPORTERS_AND_ABOVE では純 SUPPORTER（組織・チーム）にも push が届き、ジョブの include_supporters は true")
    void H22_SUPPORTERS_AND_ABOVEでは純SUPPORTERにもpushが届く() throws Exception {
        Sc sc = scenario();
        Map<String, Object> audience = new LinkedHashMap<>(groupRange(sc));
        audience.put("targetRole", "SUPPORTERS_AND_ABOVE");
        long feedId = sendSurvey(sc.xa, sc, audience);

        List<Map<String, Object>> teamsJobs = jobsOfType(jobsAfterSettling(sc.org.getId()), TEAMS_SCOPE);
        assertThat(teamsJobs).hasSize(1);
        assertThat(isTrue(teamsJobs.get(0).get("include_supporters"))).isTrue();
        runWorker(feedId);

        Map<Long, Integer> counts = notifiedAll(sc);
        assertThat(counts.get(sc.orgSupporter)).as("組織の純 SUPPORTER").isEqualTo(1);
        assertThat(counts.get(sc.teamSupporter)).as("宛先チーム T1 の純 SUPPORTER").isEqualTo(1);
    }

    private static boolean isTrue(Object raw) {
        if (raw instanceof Boolean b) {
            return b;
        }
        return ((Number) raw).intValue() != 0;
    }

    // =====================================================================
    // H31
    // =====================================================================

    @Test
    @DisplayName("H31: 同じ組織で並行する2ジョブの宛先は、ジョブごとの scope_ref（= audience_snapshot_id）で分かれ混ざらない")
    void H31_同じ組織で並行する2ジョブの宛先はscope_refごとに分かれ混ざらない() throws Exception {
        Sc sc = scenario();
        long feedA = sendSurvey(sc.xa, sc, Map.of("targetGroupIds", ids(sc.g1.getId())));
        long feedB = sendSurvey(sc.xa, sc, Map.of("targetGroupIds", ids(sc.g2.getId())));

        List<Map<String, Object>> teamsJobs = jobsOfType(jobsAfterSettling(sc.org.getId()), TEAMS_SCOPE);
        assertThat(teamsJobs).hasSize(2);
        assertThat(teamsJobs.stream().map(j -> (String) j.get("scope_ref")).collect(Collectors.toSet()))
                .as("scope_ref は告知ごとに別の audience_snapshot_id")
                .containsExactlyInAnyOrder(audienceId(feedA).toString(), audienceId(feedB).toString());
        assertThat(audienceTeamIds(audienceId(feedA))).containsExactly(sc.t1.getId());
        assertThat(audienceTeamIds(audienceId(feedB))).containsExactly(sc.t2.getId());

        runWorker(feedA);
        Map<Long, Integer> afterA = notifiedAll(sc);
        assertThat(afterA.get(sc.t1m)).isEqualTo(1);
        assertThat(afterA).as("A の配信では B の宛先 T2 に届かない").doesNotContainKey(sc.t2m);

        runWorker(feedB);
        Map<Long, Integer> afterB = notifiedAll(sc);
        assertThat(afterB.get(sc.t1m)).as("B の宛先に T1 は混ざらない（1件のまま）").isEqualTo(1);
        assertThat(afterB.get(sc.t2m)).isEqualTo(1);
        assertThat(afterB.get(sc.xo)).as("直属メンバーは両方の告知で1件ずつ").isEqualTo(2);
    }
}
