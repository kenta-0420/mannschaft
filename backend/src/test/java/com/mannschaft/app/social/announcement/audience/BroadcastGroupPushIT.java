package com.mannschaft.app.social.announcement.audience;

import com.fasterxml.jackson.databind.JsonNode;
import com.mannschaft.app.membership.domain.RoleKind;
import com.mannschaft.app.membership.domain.ScopeType;
import com.mannschaft.app.notification.NotificationPriority;
import com.mannschaft.app.notification.fanout.FanoutEnqueueCommand;
import com.mannschaft.app.notification.fanout.FanoutMessageKind;
import com.mannschaft.app.notification.fanout.NotificationFanoutJob;
import com.mannschaft.app.notification.fanout.NotificationFanoutJobRepository;
import com.mannschaft.app.notification.fanout.NotificationFanoutJobService;
import com.mannschaft.app.notification.fanout.NotificationFanoutJobStatus;
import com.mannschaft.app.notification.fanout.NotificationFanoutWorker;
import com.mannschaft.app.notification.outbox.NotificationOutboxIngestService;
import com.mannschaft.app.notification.outbox.NotificationOutboxPayload;
import com.mannschaft.app.notification.outbox.NotificationOutboxRelay;
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
import org.springframework.dao.PessimisticLockingFailureException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.util.AopTestUtils;
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
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.reset;
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
 *   <li>H21（再定義。通知 outbox）→ {@link #H21_ロールバックでoutboxも残らずコミットでoutbox1行とdrainで1組だけできる()}</li>
 *   <li>H21a → {@link #H21a_宛先集合の登録後ジョブ登録前に落ちると何も残らず再試行で1組できる()}</li>
 *   <li>H21b → {@link #H21b_relay前に宛先グループへ移ったチームは宛先集合に入らない()}</li>
 *   <li>H21c → {@link #H21c_relayが失敗してもフィードとコンテンツは残りoutboxはPENDING()}</li>
 *   <li>H21d → {@link #H21d_同じ依頼の同時取り込みでも見出し1件と宛先チーム過不足なしとジョブ1件()} ／
 *       {@link #H21d_組織が食い違う依頼は取り込みが失敗し何も足さない()}</li>
 *   <li>H21e → {@link #H21e_宛先チーム0件でも見出し1件とジョブ1件で直属メンバーにだけ届く()}</li>
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
 * <p>宛先を絞った告知の push は、告知と同じ tx で social の outbox（{@code social_notification_outbox}）に1行書かれ、
 * 宛先集合とジョブは通知ドメインの relay が取り込んだときに作られる（docs/architecture/notification_outbox.md）。
 * 試験プロファイルでは起こし（AFTER_COMMIT の即時 drain）と予備ポーラーが止まっているので、本クラスは
 * {@link NotificationOutboxRelay#drainAll()} を同期で呼んでから観測する（{@link #jobsAfterSettling}）。</p>
 *
 * <p>「ジョブが作られない」系の検証は、リスナー（AFTER_COMMIT・非同期）が遅れて enqueue する経路も
 * 捕まえるため、送信後に drain し、数秒待ってから表を SELECT する。</p>
 */
@AutoConfigureMockMvc
@EnabledIf("com.mannschaft.app.support.test.AbstractMySqlIntegrationTest#isDockerAvailable")
@DisplayName("F01.2.1 6-E グループ宛て告知の push（AC-H04・H07〜H11・H13・H18・H19・H21〜H21e・H22・H27・H28・H30・H31・H33）")
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
    @Autowired
    private NotificationOutboxRelay relay;
    @Autowired
    private NotificationOutboxIngestService ingestService;

    /** H21a・H21c でジョブの登録だけを失敗させる（それ以外は実物を呼ぶ）。 */
    @MockitoSpyBean
    private NotificationFanoutJobService fanoutJobServiceSpy;

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

    /**
     * 送信後に outbox を drain し（relay の同期呼び出し）、数秒待ってから組織に紐づく fan-out ジョブを全件返す
     * （遅れて enqueue する経路も捕まえる）。
     */
    private List<Map<String, Object>> jobsAfterSettling(long orgId) throws InterruptedException {
        relay.drainAll();
        Thread.sleep(SETTLE_MILLIS);
        return jobs(orgId);
    }

    /** 組織の social outbox の行（冪等キー・種類・状態・失敗回数・payload）。 */
    private List<Map<String, Object>> outboxRows(long orgId) {
        return jdbc.queryForList(
                "SELECT HEX(idempotency_key) AS ik, message_kind, notification_type, status, attempt_count, last_error, "
                        + "relayed_at, payload_json FROM social_notification_outbox WHERE organization_id = ?", orgId);
    }

    /** 組織の social outbox の1行目の payload の {@code teamIds}（JSON 列は MySQL が正規化するので文字列で比べず読む）。 */
    private List<Long> payloadTeamIds(long orgId) throws Exception {
        JsonNode node = objectMapper.readTree(
                (String) outboxRows(orgId).get(0).get("payload_json")).get("teamIds");
        if (node == null || node.isNull()) {
            return null;
        }
        List<Long> ids = new ArrayList<>();
        node.forEach(n -> ids.add(n.asLong()));
        return ids;
    }

    /** 失敗した outbox の行をすぐ再試行できるようにする（{@code next_attempt_at} を過去へ）。 */
    private void makeRetryableNow(long orgId) {
        jdbc.update("UPDATE social_notification_outbox "
                + "SET next_attempt_at = DATE_SUB(UTC_TIMESTAMP(6), INTERVAL 1 SECOND) "
                + "WHERE organization_id = ? AND status = 'PENDING'", orgId);
    }

    /** 冪等キーで引いたジョブ行（親・子シャードを含む）の件数。 */
    private long jobCountByKey(UUID key) {
        Long n = jdbc.queryForObject(
                "SELECT COUNT(*) FROM notification_fanout_jobs WHERE source_event_uuid = UNHEX(?)", Long.class, hex(key));
        return n == null ? 0L : n;
    }

    private long audienceHeaderCountById(UUID audienceId) {
        Long n = jdbc.queryForObject(
                "SELECT COUNT(*) FROM notification_fanout_audiences WHERE audience_snapshot_id = UNHEX(?)",
                Long.class, hex(audienceId));
        return n == null ? 0L : n;
    }

    /**
     * ジョブの登録（{@code enqueueInCurrentTransaction}）を失敗させる（{@code always} でなければ次の1回だけ）。
     * MANDATORY のメソッドなので、プロキシ越しにスタブすると tx の外で弾かれる。AOP の内側の spy に直接スタブする。
     */
    private void failJobEnqueue(boolean always) {
        NotificationFanoutJobService target = AopTestUtils.getUltimateTargetObject(fanoutJobServiceSpy);
        if (always) {
            doThrow(new IllegalStateException("ジョブ登録の失敗（テスト）"))
                    .when(target).enqueueInCurrentTransaction(any());
        } else {
            doThrow(new IllegalStateException("ジョブ登録の失敗（テスト）")).doCallRealMethod()
                    .when(target).enqueueInCurrentTransaction(any());
        }
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
                bulletinBody(Map.of("targetTeamIds", List.of(sc.t1.getSlug(), sc.t2.getSlug()))))
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

        long feedId = sendSurvey(sc.xm, sc, Map.of("targetTeamIds", List.of(sc.t1.getSlug(), sc.t2.getSlug())));

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
        long feedId = sendSurvey(sc.xa, sc, Map.of("targetTeamIds", List.of(sc.t1.getSlug(), sc.t2.getSlug())));

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
    @DisplayName("H21: 告知が巻き戻ると social outbox の行も残らない。コミットすると outbox 1行が告知と同時に確定し、relay の前はジョブ0件、drain で ORGANIZATION_TEAMS のジョブと宛先集合がちょうど1組、drain を繰り返しても1組のまま")
    void H21_ロールバックでoutboxも残らずコミットでoutbox1行とdrainで1組だけできる() throws Exception {
        Sc sc = scenario();
        long orgId = sc.org.getId();

        // コミット側: 告知と同時に outbox 1行。relay の前は通知ドメインに何も無い
        long feedId = sendSurvey(sc.xa, sc, groupRange(sc));
        List<Map<String, Object>> rows = outboxRows(orgId);
        assertThat(rows).as("告知と同時に outbox がちょうど1行").hasSize(1);
        assertThat(rows.get(0).get("ik")).as("冪等キーはフィード ID から導いた決定的な UUID")
                .isEqualTo(hex(broadcastKey(feedId)));
        assertThat(rows.get(0).get("message_kind")).isEqualTo("FANOUT_WITH_AUDIENCE");
        assertThat(rows.get(0).get("notification_type")).isEqualTo(SURVEY_TYPE);
        assertThat(rows.get(0).get("status")).isEqualTo("PENDING");
        assertThat(jobs(orgId)).as("relay の前はジョブ0件").isEmpty();
        assertThat(audienceHeaderCount(orgId)).as("relay の前は宛先集合0件").isZero();

        // drain で1組
        assertThat(jobsOfType(jobsAfterSettling(orgId), TEAMS_SCOPE)).hasSize(1);
        assertThat(audienceHeaderCount(orgId)).isEqualTo(1L);
        assertThat(audienceTeamIds(audienceId(feedId))).containsExactlyInAnyOrder(sc.t1.getId(), sc.t2.getId());
        assertThat(outboxRows(orgId).get(0).get("status")).isEqualTo("RELAYED");
        assertThat(outboxRows(orgId).get(0).get("relayed_at")).isNotNull();

        // 取り込みの後・印付けの前に落ちた（行が PENDING に戻った）状態から drain を2回繰り返しても1組のまま
        jdbc.update("UPDATE social_notification_outbox SET status = 'PENDING', claim_token = NULL, claimed_at = NULL, "
                + "relayed_at = NULL WHERE organization_id = ?", orgId);
        relay.drainAll();
        relay.drainAll();
        assertThat(jobsOfType(jobs(orgId), TEAMS_SCOPE)).as("再取り込みでもジョブは1件").hasSize(1);
        assertThat(jobCountByKey(broadcastKey(feedId))).isEqualTo(1L);
        assertThat(audienceHeaderCount(orgId)).as("宛先集合の見出しも1件").isEqualTo(1L);
        assertThat(audienceTeamIds(audienceId(feedId))).as("宛先チームは過不足なし")
                .containsExactlyInAnyOrder(sc.t1.getId(), sc.t2.getId());
        assertThat(outboxRows(orgId)).hasSize(1);
        assertThat(outboxRows(orgId).get(0).get("status")).isEqualTo("RELAYED");

        // ロールバック側: outbox もジョブも宛先集合も増えない
        long feedsBefore = feedCount(orgId);
        BroadcastAudienceSpec spec = new BroadcastAudienceSpec(
                null, List.of(sc.g3.getId()), null, null, null, "MEMBERS_AND_ABOVE");
        ResolvedBroadcastAudience resolved =
                audienceResolver.resolveForBroadcast(
                        sc.xa, "ORGANIZATION", orgId, spec, AnnouncementChannel.SURVEY);
        assertThat(resolved.pushEnabled()).as("対照: 巻き戻す告知も push を出す条件を満たす").isTrue();
        BroadcastRequest request = BroadcastRequest.builder()
                .channel(AnnouncementChannel.SURVEY)
                .targetRole("MEMBERS_AND_ABOVE")
                .priority("NORMAL")
                .content(AnnouncementContentRequest.builder().title("巻き戻す告知").description("説明").build())
                .callerUserId(sc.xa)
                .scopeType("ORGANIZATION")
                .scopeId(orgId)
                .audience(resolved)
                .build();
        new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
            broadcastService.broadcast(request);
            assertThat(outboxRowsInTx(orgId)).as("巻き戻す前は同じ tx の中に outbox の行が見える").isEqualTo(2L);
            status.setRollbackOnly();
        });

        assertThat(feedCount(orgId)).as("フィードは巻き戻る").isEqualTo(feedsBefore);
        assertThat(outboxRows(orgId)).as("outbox の行も巻き戻る（最初の1行だけが残る）").hasSize(1);
        assertThat(jobsOfType(jobsAfterSettling(orgId), TEAMS_SCOPE))
                .as("drain してもジョブは増えない（最初の1件だけが残る）").hasSize(1);
        assertThat(audienceHeaderCount(orgId)).as("宛先集合の見出しも増えない").isEqualTo(1L);
    }

    /** 同じ tx（呼び出し側の接続）から見える outbox の行数。 */
    private long outboxRowsInTx(long orgId) {
        return ((Number) em.createNativeQuery(
                        "SELECT COUNT(*) FROM social_notification_outbox WHERE organization_id = :o")
                .setParameter("o", orgId).getSingleResult()).longValue();
    }

    @Test
    @DisplayName("H21a: 取り込みで宛先集合を登録した後・ジョブを登録する前に失敗すると、宛先集合もジョブも残らず outbox は PENDING（attempt=1）。再試行で1組だけできる")
    void H21a_宛先集合の登録後ジョブ登録前に落ちると何も残らず再試行で1組できる() throws Exception {
        Sc sc = scenario();
        long orgId = sc.org.getId();
        long feedId = sendSurvey(sc.xa, sc, groupRange(sc));
        failJobEnqueue(false);

        relay.drainAll();

        assertThat(audienceHeaderCountById(audienceId(feedId))).as("宛先集合の見出しも巻き戻る（同じ tx）").isZero();
        assertThat(audienceTeamIds(audienceId(feedId))).as("宛先チームも巻き戻る").isEmpty();
        assertThat(jobCountByKey(broadcastKey(feedId))).isZero();
        Map<String, Object> row = outboxRows(orgId).get(0);
        assertThat(row.get("status")).isEqualTo("PENDING");
        assertThat(((Number) row.get("attempt_count")).intValue()).isEqualTo(1);
        assertThat((String) row.get("last_error")).contains("ジョブ登録の失敗");

        makeRetryableNow(orgId);
        relay.drainAll();

        assertThat(jobCountByKey(broadcastKey(feedId))).as("再試行でジョブ1件").isEqualTo(1L);
        assertThat(audienceHeaderCountById(audienceId(feedId))).isEqualTo(1L);
        assertThat(audienceTeamIds(audienceId(feedId))).containsExactlyInAnyOrder(sc.t1.getId(), sc.t2.getId());
        assertThat(outboxRows(orgId).get(0).get("status")).isEqualTo("RELAYED");
    }

    @Test
    @DisplayName("H21b: 送信後・relay の前にチーム(T4)を宛先グループ(G1)へ移しても、宛先集合は送信時のチーム(T1)のまま（payload に送信時の teamIds）")
    void H21b_relay前に宛先グループへ移ったチームは宛先集合に入らない() throws Exception {
        Sc sc = scenario();
        long feedId = sendSurvey(sc.xa, sc, Map.of("targetGroupIds", ids(sc.g1.getId())));
        assertThat(payloadTeamIds(sc.org.getId())).as("payload に送信時の宛先チームが入る")
                .containsExactly(sc.t1.getId());

        moveTeamToGroup(sc.t4.getId(), sc.org.getId(), sc.g1.getId());
        jobsAfterSettling(sc.org.getId());

        assertThat(audienceTeamIds(audienceId(feedId))).as("宛先集合は送信時のチームだけ").containsExactly(sc.t1.getId());
        runWorker(feedId);
        Map<Long, Integer> counts = notifiedAll(sc);
        assertThat(counts.get(sc.t1m)).isEqualTo(1);
        assertThat(counts).as("relay の前に G1 へ移った T4 のメンバーには届かない").doesNotContainKey(sc.t4m);
    }

    @Test
    @DisplayName("H21c: relay（取り込み）が失敗しても、告知のフィードとコンテンツ（アンケート）は残り、outbox は PENDING のまま再試行を待つ")
    void H21c_relayが失敗してもフィードとコンテンツは残りoutboxはPENDING() throws Exception {
        Sc sc = scenario();
        long orgId = sc.org.getId();
        ResultActions result = broadcastToOrg(sc.xa, orgId, body("SURVEY", groupRange(sc)));
        result.andExpect(status().isCreated());
        long feedId = feedIdOf(result);
        long contentId = objectMapper.readTree(result.andReturn().getResponse().getContentAsString())
                .path("data").path("contentId").asLong();
        failJobEnqueue(true);

        relay.drainAll();

        Long feeds = jdbc.queryForObject("SELECT COUNT(*) FROM announcement_feeds WHERE id = ?", Long.class, feedId);
        assertThat(feeds).as("フィードは残る").isEqualTo(1L);
        Long surveys = jdbc.queryForObject("SELECT COUNT(*) FROM surveys WHERE id = ?", Long.class, contentId);
        assertThat(surveys).as("コンテンツ（アンケート）は残る").isEqualTo(1L);
        Map<String, Object> row = outboxRows(orgId).get(0);
        assertThat(row.get("status")).isEqualTo("PENDING");
        assertThat(((Number) row.get("attempt_count")).intValue()).isEqualTo(1);
        assertThat(jobCountByKey(broadcastKey(feedId))).isZero();
        assertThat(audienceHeaderCountById(audienceId(feedId))).isZero();

        // 失敗の注入を外すと再試行で取り込まれる（他のテストの drain に PENDING を残さない）
        reset(AopTestUtils.<NotificationFanoutJobService>getUltimateTargetObject(fanoutJobServiceSpy));
        makeRetryableNow(orgId);
        relay.drainAll();
        assertThat(outboxRows(orgId).get(0).get("status")).isEqualTo("RELAYED");
        assertThat(jobCountByKey(broadcastKey(feedId))).isEqualTo(1L);
    }

    @Test
    @DisplayName("H21d: 同じ依頼を4本同時に取り込んでも、見出し1件・宛先チームは過不足なし・ジョブ1件（デッドロックは再試行で収まる）")
    void H21d_同じ依頼の同時取り込みでも見出し1件と宛先チーム過不足なしとジョブ1件() throws Exception {
        Sc sc = scenario();
        UUID audience = UUID.randomUUID();
        UUID key = UUID.randomUUID();
        List<Long> teams = List.of(sc.t3.getId(), sc.t1.getId(), sc.t2.getId());
        NotificationOutboxPayload payload = NotificationOutboxPayload.fanoutWithAudience(
                surveyCommand(audience, key, sc.org.getId()), audience, teams);

        int threads = 4;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<Integer>> results = new ArrayList<>();
        try {
            for (int i = 0; i < threads; i++) {
                results.add(pool.submit(() -> {
                    start.await();
                    return ingestWithRetry(payload);
                }));
            }
            start.countDown();
            for (Future<Integer> f : results) {
                assertThat(f.get(60, TimeUnit.SECONDS)).as("再試行の回数（上限内で成功）").isLessThan(5);
            }
        } finally {
            pool.shutdownNow();
        }

        assertThat(audienceHeaderCountById(audience)).isEqualTo(1L);
        assertThat(audienceTeamIds(audience)).containsExactlyInAnyOrderElementsOf(teams);
        Long teamRows = jdbc.queryForObject(
                "SELECT COUNT(*) FROM notification_fanout_audience_teams WHERE audience_snapshot_id = UNHEX(?)",
                Long.class, hex(audience));
        assertThat(teamRows).as("宛先チームの行は重複しない").isEqualTo(3L);
        assertThat(jobCountByKey(key)).isEqualTo(1L);
    }

    @Test
    @DisplayName("H21d(補): 既存の宛先集合と組織が食い違う依頼は取り込みが失敗し、宛先チームもジョブも足さない")
    void H21d_組織が食い違う依頼は取り込みが失敗し何も足さない() {
        Sc sc = scenario();
        UUID audience = UUID.randomUUID();
        ingestService.ingest(NotificationOutboxPayload.fanoutWithAudience(
                surveyCommand(audience, UUID.randomUUID(), sc.org.getId()), audience, List.of(sc.t1.getId())));
        UUID otherKey = UUID.randomUUID();

        assertThatThrownBy(() -> ingestService.ingest(NotificationOutboxPayload.fanoutWithAudience(
                surveyCommand(audience, otherKey, sc.orgY.getId()), audience, List.of(sc.t2.getId()))))
                .isInstanceOf(IllegalStateException.class);

        assertThat(audienceTeamIds(audience)).as("他組織の依頼で宛先チームは増えない").containsExactly(sc.t1.getId());
        assertThat(jobCountByKey(otherKey)).as("食い違った依頼のジョブは作られない").isZero();
    }

    /** 同時取り込みでロックの競合（デッドロック・待ちタイムアウト）が起きたら再試行する（relay の再試行と同じ扱い）。 */
    private int ingestWithRetry(NotificationOutboxPayload payload) {
        for (int attempt = 0; ; attempt++) {
            try {
                ingestService.ingest(payload);
                return attempt;
            } catch (PessimisticLockingFailureException e) {
                if (attempt >= 4) {
                    throw e;
                }
            }
        }
    }

    /** H21d 用の取り込み依頼（告知 push と同じ形。scope_ref は宛先集合のキー）。 */
    private static FanoutEnqueueCommand surveyCommand(UUID audience, UUID key, long orgId) {
        return new FanoutEnqueueCommand(TEAMS_SCOPE, audience.toString(), SURVEY_TYPE, key, orgId,
                NotificationPriority.NORMAL, null, "SURVEY", 1L, "/surveys/1", false,
                FanoutMessageKind.SURVEY_PUBLISHED, List.of("同時取り込み"), FanoutEnqueueCommand.ShardMode.AUTO);
    }

    @Test
    @DisplayName("H21e: 宛先チームが0件のグループ宛てでも、見出し1件・ジョブ1件（ORGANIZATION_TEAMS）で、組織の直属メンバーにだけ届き、組織全体への fan-out（ORGANIZATION ジョブ・チームのメンバーへの配信）は出ない")
    void H21e_宛先チーム0件でも見出し1件とジョブ1件で直属メンバーにだけ届く() throws Exception {
        Sc sc = scenario();
        OrgTeamGroupEntity empty = new TransactionTemplate(transactionManager)
                .execute(status -> newGroup(sc.org.getId(), "G空", 9));
        long feedId = sendSurvey(sc.xa, sc, Map.of("targetGroupIds", ids(empty.getId())));
        assertThat(payloadTeamIds(sc.org.getId())).as("payload の宛先チームは空（null ではなく空配列）").isNotNull().isEmpty();

        List<Map<String, Object>> all = jobsAfterSettling(sc.org.getId());
        assertThat(jobsOfType(all, ORG_SCOPE)).as("組織全体への ORGANIZATION ジョブは出ない").isEmpty();
        assertThat(jobsOfType(all, TEAMS_SCOPE)).as("ORGANIZATION_TEAMS ジョブがちょうど1件").hasSize(1);
        assertThat(audienceHeaderCount(sc.org.getId())).as("宛先集合の見出しが1件").isEqualTo(1L);
        assertThat(audienceTeamIds(audienceId(feedId))).as("宛先チームは0件").isEmpty();

        runWorker(feedId);

        Map<Long, Integer> counts = notifiedAll(sc);
        for (long direct : List.of(sc.xo, sc.xm, sc.xd, sc.xd2, sc.sa, sc.t1xo)) {
            assertThat(counts.get(direct)).as("組織の直属メンバーには届く userId=" + direct).isEqualTo(1);
        }
        for (long teamOnly : List.of(sc.t1m, sc.t2m, sc.t3m, sc.t0m, sc.t4m, sc.t12m, sc.teamSupporter)) {
            assertThat(counts).as("チームだけのメンバーには届かない（組織全体へ広がらない） userId=" + teamOnly)
                    .doesNotContainKey(teamOnly);
        }
        for (long outside : List.of(sc.outsider, sc.ym)) {
            assertThat(counts).as("組織の外には届かない userId=" + outside).doesNotContainKey(outside);
        }
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
