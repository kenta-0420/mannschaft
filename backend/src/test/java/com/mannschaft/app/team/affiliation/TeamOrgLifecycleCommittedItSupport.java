package com.mannschaft.app.team.affiliation;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.mannschaft.app.notification.fanout.NotificationFanoutJobRepository;
import com.mannschaft.app.notification.fanout.NotificationFanoutJobStatus;
import com.mannschaft.app.notification.fanout.NotificationFanoutWorker;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.nio.ByteBuffer;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * F01.2.1 部隊 2-D の、コミットを伴う統合テスト（離脱・除名の通知、アーカイブ・削除の片付け、並行 IT）が共有する土台。
 *
 * <p>2-B2 の {@code OrgTeamApplicationReviewCommittedIT} と同じ作法で、テストメソッドにトランザクションを張らず、
 * フィクスチャはトランザクションを分けてコミットし、{@code @AfterEach} で物理削除する。AFTER_COMMIT のイベント処理と
 * Worker による通知配信、行ロックをまたぐ並行を、実 MySQL・実 Security・実 API で確かめるために必要。</p>
 *
 * <p>人物は設計書 §16 の記号に揃える。TA＝チーム ADMIN、TG＝権限グループで加盟操作権限を付与された MEMBER、
 * TM＝付与なしの MEMBER、TD＝付与なしの DEPUTY_ADMIN、XA・XA2＝組織 ADMIN、XD＝組織 DEPUTY_ADMIN。</p>
 */
abstract class TeamOrgLifecycleCommittedItSupport extends TeamAffiliationItSupport {

    @Autowired
    protected MockMvc mockMvc;

    @Autowired
    protected ObjectMapper objectMapper;

    @Autowired
    protected PlatformTransactionManager transactionManager;

    @Autowired
    protected JdbcTemplate jdbc;

    @Autowired
    protected NotificationFanoutJobRepository jobRepository;

    @Autowired
    protected NotificationFanoutWorker worker;

    // =====================================================================
    // フィクスチャ
    // =====================================================================

    /** 1組のフィクスチャ（チーム T・組織 X と人物）。 */
    protected record Fixture(TeamFx team, OrgFx org, long ta, long tg, long tm, long td,
                             long xa, long xa2, long xd) {
    }

    /** チーム T（TA・TG・TM・TD）と組織 X（XA・XA2・XD）。X はグループ機能 on（OPTIONAL）。 */
    protected Fixture fixture() {
        TeamFx team = inTx(this::newTeam);
        OrgFx org = inTx(() -> newOrg(true, true, "OPTIONAL", "PUBLIC"));
        return inTx(() -> {
            seedAffiliationPermission();
            long ta = newUser();
            long tg = newUser();
            long tm = newUser();
            long td = newUser();
            long xa = newUser();
            long xa2 = newUser();
            long xd = newUser();
            makeTeamAdmin(ta, team.id());
            makeTeamMember(tg, team.id());
            grantAffiliationByPermissionGroup(tg, team.id(), "MEMBER");
            makeTeamMember(tm, team.id());
            makeTeamDeputy(td, team.id());
            makeOrgAdmin(xa, org.id());
            makeOrgAdmin(xa2, org.id());
            makeOrgDeputy(xd, org.id());
            return new Fixture(team, org, ta, tg, tm, td, xa, xa2, xd);
        });
    }

    /** もう1つのチーム（ADMIN 1名つき）。戻り値は {チーム, ADMIN の userId}。 */
    protected TeamWithAdmin extraTeam() {
        TeamFx team = inTx(this::newTeam);
        long admin = inTx(() -> {
            long u = newUser();
            makeTeamAdmin(u, team.id());
            return u;
        });
        return new TeamWithAdmin(team, admin);
    }

    protected record TeamWithAdmin(TeamFx team, long admin) {
    }

    protected void makeOrgDeputy(long userId, long orgId) {
        com.mannschaft.app.support.test.MembershipTestHelper.insertMembership(em, userId,
                com.mannschaft.app.membership.domain.ScopeType.ORGANIZATION, orgId,
                com.mannschaft.app.membership.domain.RoleKind.MEMBER);
        com.mannschaft.app.support.test.MembershipTestHelper.insertUserRole(em, userId, "DEPUTY_ADMIN", null, orgId);
    }

    /** 加盟の行を、トランザクションを分けてコミットする。 */
    protected long membership(long teamId, long orgId, String status, String direction, UUID groupId) {
        return inTx(() -> insertMembershipRow(teamId, orgId, status, direction, groupId, LocalDateTime.now()));
    }

    /** 制限（BLOCK）の行を native INSERT する（再申請の抑止。2-D の片付け対象）。 */
    protected void blockRestriction(long teamId, long orgId, String direction) {
        inTxVoid(() -> em.createNativeQuery(
                        "INSERT INTO team_org_affiliation_restrictions (id, organization_id, team_id, direction, kind, "
                                + "reason, restricted_until, created_at, updated_at) "
                                + "VALUES (UNHEX(REPLACE(UUID(),'-','')), :orgId, :teamId, :direction, 'BLOCK', "
                                + "'REJECTED', NULL, UTC_TIMESTAMP(), UTC_TIMESTAMP())")
                .setParameter("orgId", orgId)
                .setParameter("teamId", teamId)
                .setParameter("direction", direction)
                .executeUpdate());
    }

    // =====================================================================
    // API
    // =====================================================================

    /** API の結果（HTTP ステータス・エラーコード）。 */
    protected record Result(int status, String errorCode) {
    }

    protected Result toResult(MvcResult result) throws Exception {
        int status = result.getResponse().getStatus();
        String content = result.getResponse().getContentAsString();
        if (content == null || content.isBlank()) {
            return new Result(status, null);
        }
        JsonNode body = objectMapper.readTree(content);
        return new Result(status, body.path("error").path("code").asText(null));
    }

    protected Result approve(long actor, String orgSlug, long membershipId) throws Exception {
        return toResult(mockMvc.perform(post("/api/v1/organizations/{slug}/team-applications/{id}/approve",
                        orgSlug, membershipId)
                .with(user(String.valueOf(actor)))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"overrideGroup\":false}")).andReturn());
    }

    /** チーム側の申請（POST /teams/{teamSlug}/org-applications）。 */
    protected Result apply(long actor, String teamSlug, String orgSlug) throws Exception {
        return toResult(mockMvc.perform(post("/api/v1/teams/{teamSlug}/org-applications", teamSlug)
                .with(user(String.valueOf(actor)))
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(Map.of("organizationSlug", orgSlug)))).andReturn());
    }

    /** 組織側の招待（POST /organizations/{slug}/team-invites。2-C）。 */
    protected Result invite(long actor, String orgSlug, String teamSlug) throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("teamSlug", teamSlug);
        return toResult(mockMvc.perform(post("/api/v1/organizations/{slug}/team-invites", orgSlug)
                .with(user(String.valueOf(actor)))
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(body))).andReturn());
    }

    protected Result archiveTeamApi(long actor, String teamSlug) throws Exception {
        return toResult(mockMvc.perform(patch("/api/v1/teams/{slug}/archive", teamSlug)
                .with(user(String.valueOf(actor)))).andReturn());
    }

    protected Result archiveOrgApi(long actor, String orgSlug) throws Exception {
        return toResult(mockMvc.perform(patch("/api/v1/organizations/{slug}/archive", orgSlug)
                .with(user(String.valueOf(actor)))).andReturn());
    }

    protected Result deleteTeamApi(long actor, String teamSlug) throws Exception {
        return toResult(mockMvc.perform(delete("/api/v1/teams/{slug}", teamSlug)
                .with(user(String.valueOf(actor)))).andReturn());
    }

    protected Result deleteOrgApi(long actor, String orgSlug) throws Exception {
        return toResult(mockMvc.perform(delete("/api/v1/organizations/{slug}", orgSlug)
                .with(user(String.valueOf(actor)))).andReturn());
    }

    /** チーム側の離脱（DELETE /teams/{teamSlug}/organizations/{orgSlug}。§6.6）。 */
    protected Result leave(long actor, String teamSlug, String orgSlug) throws Exception {
        return toResult(mockMvc.perform(delete("/api/v1/teams/{teamSlug}/organizations/{orgSlug}", teamSlug, orgSlug)
                .with(user(String.valueOf(actor)))).andReturn());
    }

    /** 組織側の除名（DELETE /organizations/{slug}/teams/{teamSlug}。§6.6）。 */
    protected Result remove(long actor, String orgSlug, String teamSlug) throws Exception {
        return toResult(mockMvc.perform(delete("/api/v1/organizations/{slug}/teams/{teamSlug}", orgSlug, teamSlug)
                .with(user(String.valueOf(actor)))).andReturn());
    }

    // =====================================================================
    // 並行・トランザクション
    // =====================================================================

    /** 全タスクを同時にスタートさせ、結果を投入順に返す。デッドロックなら get のタイムアウトで落ちる。 */
    protected <T> List<T> runConcurrently(List<Callable<T>> tasks) throws Exception {
        ExecutorService executor = Executors.newFixedThreadPool(tasks.size());
        try {
            CountDownLatch ready = new CountDownLatch(tasks.size());
            CountDownLatch start = new CountDownLatch(1);
            List<Future<T>> futures = new ArrayList<>();
            for (Callable<T> task : tasks) {
                futures.add(executor.submit(() -> {
                    ready.countDown();
                    start.await();
                    return task.call();
                }));
            }
            assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
            start.countDown();
            List<T> results = new ArrayList<>();
            for (Future<T> future : futures) {
                results.add(future.get(60, TimeUnit.SECONDS));
            }
            return results;
        } finally {
            executor.shutdownNow();
        }
    }

    /** フィクスチャ作りなどをトランザクションに包んでコミットする。 */
    protected <T> T inTx(Supplier<T> action) {
        return new TransactionTemplate(transactionManager).execute(status -> action.get());
    }

    protected void inTxVoid(Runnable action) {
        new TransactionTemplate(transactionManager).executeWithoutResult(status -> action.run());
    }

    /**
     * 別スレッドでトランザクションを開き、{@code action} を実行したままコミットを保留する（行ロックの保持）。
     * 「作成側が先にロックを取った」「アーカイブ側が先にロックを取った」の順序を、時間の偶然に頼らず作るために使う。
     */
    protected final class HeldTx {
        private final CountDownLatch locked = new CountDownLatch(1);
        private final CountDownLatch release = new CountDownLatch(1);
        private final Future<?> future;

        protected HeldTx(ExecutorService executor, Runnable action) throws Exception {
            future = executor.submit(() -> new TransactionTemplate(transactionManager)
                    .executeWithoutResult(status -> {
                        action.run();
                        locked.countDown();
                        try {
                            if (!release.await(60, TimeUnit.SECONDS)) {
                                throw new IllegalStateException("保留したトランザクションが解放されなかった");
                            }
                        } catch (InterruptedException e) {
                            Thread.currentThread().interrupt();
                            throw new IllegalStateException(e);
                        }
                    }));
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(20);
            while (!locked.await(100, TimeUnit.MILLISECONDS)) {
                if (future.isDone()) {
                    future.get(); // 保留に入る前に失敗したなら、その例外を出す
                }
                assertThat(System.nanoTime()).as("ロックの保持に入れなかった").isLessThan(deadline);
            }
        }

        /** 保留を解いてコミットさせる。 */
        protected void commit() throws Exception {
            release.countDown();
            future.get(60, TimeUnit.SECONDS);
        }
    }

    /** 条件が成り立つまで待つ（AFTER_COMMIT・非同期リスナーの完了待ち）。 */
    protected void awaitCondition(String what, BooleanSupplier condition) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(20);
        while (!condition.getAsBoolean()) {
            assertThat(System.nanoTime()).as("%s（20秒待っても成り立たない）", what).isLessThan(deadline);
            Thread.sleep(100);
        }
    }

    // =====================================================================
    // 状態の読み取り（コミット済みの値を JDBC で読む）
    // =====================================================================

    protected String statusOf(long membershipId) {
        List<String> rows = jdbc.queryForList(
                "SELECT status FROM team_org_memberships WHERE id = ?", String.class, membershipId);
        return rows.isEmpty() ? null : rows.get(0);
    }

    protected long rowCount(long teamId, long orgId) {
        Long count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM team_org_memberships WHERE team_id = ? AND organization_id = ?",
                Long.class, teamId, orgId);
        return count == null ? 0 : count;
    }

    protected long pendingCount(long teamId, long orgId) {
        Long count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM team_org_memberships WHERE team_id = ? AND organization_id = ? "
                        + "AND status = 'PENDING'", Long.class, teamId, orgId);
        return count == null ? 0 : count;
    }

    protected long restrictionCountOfOrg(long orgId) {
        Long count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM team_org_affiliation_restrictions WHERE organization_id = ?", Long.class, orgId);
        return count == null ? 0 : count;
    }

    protected long restrictionCountOfTeam(long teamId) {
        Long count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM team_org_affiliation_restrictions WHERE team_id = ?", Long.class, teamId);
        return count == null ? 0 : count;
    }

    // =====================================================================
    // 通知（fan-out ジョブと配信）
    // =====================================================================

    protected List<UUID> jobIdsOf(long membershipId, String notificationType) {
        List<byte[]> raws = jdbc.queryForList(
                "SELECT id FROM notification_fanout_jobs WHERE source_type = 'TEAM_ORG_MEMBERSHIP' "
                        + "AND source_id = ? AND notification_type = ?", byte[].class, membershipId, notificationType);
        List<UUID> ids = new ArrayList<>();
        for (byte[] raw : raws) {
            ByteBuffer buffer = ByteBuffer.wrap(raw);
            ids.add(new UUID(buffer.getLong(), buffer.getLong()));
        }
        return ids;
    }

    protected UUID singleJob(long membershipId, String notificationType) {
        List<UUID> ids = jobIdsOf(membershipId, notificationType);
        assertThat(ids).as("%s の通知ジョブは1件（membershipId=%d）", notificationType, membershipId).hasSize(1);
        return ids.get(0);
    }

    /** scope_type, scope_ref, action_url, organization_id。 */
    protected Map<String, Object> jobRow(UUID jobId) {
        return jdbc.queryForMap(
                "SELECT scope_type, scope_ref, action_url, organization_id FROM notification_fanout_jobs WHERE id = ?",
                uuidBytes(jobId));
    }

    /** ジョブを Worker で配信し、DONE になったことを確かめる。 */
    protected void deliver(UUID jobId) {
        worker.processOne(jobRepository.findById(jobId).orElseThrow());
        assertThat(jobRepository.findById(jobId).orElseThrow().getStatus())
                .isEqualTo(NotificationFanoutJobStatus.DONE);
    }

    /** 指定ユーザーに届いた、指定種別の通知（user_id, action_url, source_type, body）。 */
    protected List<Map<String, Object>> notificationsOf(String type, long... userIds) {
        StringBuilder in = new StringBuilder();
        List<Object> args = new ArrayList<>();
        args.add(type);
        for (long id : userIds) {
            in.append(in.length() == 0 ? "?" : ",?");
            args.add(id);
        }
        return jdbc.queryForList(
                "SELECT user_id, action_url, source_type, body FROM notifications WHERE notification_type = ? "
                        + "AND user_id IN (" + in + ")", args.toArray());
    }

    protected static List<Long> userIdsOf(List<Map<String, Object>> rows) {
        return rows.stream().map(r -> ((Number) r.get("user_id")).longValue()).toList();
    }

    protected static byte[] uuidBytes(UUID id) {
        ByteBuffer buffer = ByteBuffer.allocate(16);
        buffer.putLong(id.getMostSignificantBits());
        buffer.putLong(id.getLeastSignificantBits());
        return buffer.array();
    }
}
