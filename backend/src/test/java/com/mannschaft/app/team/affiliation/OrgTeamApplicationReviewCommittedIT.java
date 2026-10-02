package com.mannschaft.app.team.affiliation;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.mannschaft.app.notification.fanout.NotificationFanoutJob;
import com.mannschaft.app.notification.fanout.NotificationFanoutJobRepository;
import com.mannschaft.app.notification.fanout.NotificationFanoutJobStatus;
import com.mannschaft.app.notification.fanout.NotificationFanoutWorker;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
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
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * F01.2.1 部隊 2-B2 — コミットを伴う検証（承認・拒否・取下げの並行競合と、通知の配信）の統合テスト（試練）。
 *
 * <p>{@link OrgTeamApplicationReviewIT} は {@code @Transactional} でロールバックするため、トランザクションをまたぐ
 * 競合と Worker による配信は検証できない。本クラスはテストメソッドにトランザクションを張らず、
 * フィクスチャはトランザクションを分けてコミットし、{@link #cleanUp()} で物理削除する。</p>
 *
 * <ul>
 *   <li>AC-B14: 取下げと承認（および拒否と承認）を並行に実行すると、ちょうど一方だけが成功し、
 *       負けた側は §6.4 の判定表どおり 404 {@code TEAM_070}（行が消えた）か 409 {@code TEAM_071}（ACTIVE になった）</li>
 *   <li>AC-C01・G117b・P07: 承認の通知はチームの加盟操作者（TA・TG）にだけ届き、TM・TD には届かない</li>
 *   <li>AC-C07・G117c・P07: 拒否の通知も同じ受信者に届き、理由を付けたときだけ本文に理由が入る</li>
 * </ul>
 */
@AutoConfigureMockMvc
@EnabledIf("com.mannschaft.app.support.test.AbstractMySqlIntegrationTest#isDockerAvailable")
@DisplayName("F01.2.1 2-B2 承認・拒否の並行競合と通知の配信（コミットを伴う検証）")
class OrgTeamApplicationReviewCommittedIT extends TeamAffiliationItSupport {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private PlatformTransactionManager transactionManager;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private NotificationFanoutJobRepository jobRepository;

    @Autowired
    private NotificationFanoutWorker worker;

    @AfterEach
    void cleanUp() {
        deleteCommittedFixtures(jdbc);
    }

    // =====================================================================
    // AC-B14 並行の競合（ちょうど一方だけが成功する）
    // =====================================================================

    @Test
    @DisplayName("AC-B14 取下げと承認を並行に実行すると、ちょうど一方だけが成功し、負けた側は 404 TEAM_070 か 409 TEAM_071")
    void 取下げと承認の並行はちょうど一方だけ成功する() throws Exception {
        for (int round = 0; round < 3; round++) {
            Fixture fx = fixture();
            long id = inTx(() -> insertMembershipRow(fx.team().id(), fx.org().id(), "PENDING", "TEAM_APPLY", null,
                    LocalDateTime.now()));

            List<Result> results = runConcurrently(List.of(
                    () -> approve(fx.xa(), fx.org().slug(), id),
                    () -> withdraw(fx.ta(), fx.team().slug(), id)));
            Result approved = results.get(0);
            Result withdrawn = results.get(1);

            boolean approveWon = approved.status() == 200;
            boolean withdrawWon = withdrawn.status() == 204;
            assertThat(approveWon ^ withdrawWon).as("ちょうど一方だけが成功する: %s", results).isTrue();
            if (approveWon) {
                assertThat(withdrawn.status()).isEqualTo(409);
                assertThat(withdrawn.errorCode()).isEqualTo("TEAM_071");
                assertThat(statusOf(id)).isEqualTo("ACTIVE");
            } else {
                assertThat(approved.status()).isEqualTo(404);
                assertThat(approved.errorCode()).isEqualTo("TEAM_070");
                assertThat(statusOf(id)).as("取下げで行は消える").isNull();
            }
        }
    }

    @Test
    @DisplayName("AC-B14 拒否と承認を並行に実行すると、ちょうど一方だけが成功し、ACTIVE と制限が同時に残ることはない")
    void 拒否と承認の並行はちょうど一方だけ成功する() throws Exception {
        for (int round = 0; round < 3; round++) {
            Fixture fx = fixture();
            long id = inTx(() -> insertMembershipRow(fx.team().id(), fx.org().id(), "PENDING", "TEAM_APPLY", null,
                    LocalDateTime.now()));

            List<Result> results = runConcurrently(List.of(
                    () -> approve(fx.xa(), fx.org().slug(), id),
                    () -> reject(fx.xa(), fx.org().slug(), id)));
            Result approved = results.get(0);
            Result rejected = results.get(1);

            boolean approveWon = approved.status() == 200;
            boolean rejectWon = rejected.status() == 200;
            assertThat(approveWon ^ rejectWon).as("ちょうど一方だけが成功する: %s", results).isTrue();
            long restrictions = restrictionCount(fx.team().id(), fx.org().id());
            if (approveWon) {
                assertThat(rejected.status()).isEqualTo(409);
                assertThat(rejected.errorCode()).isEqualTo("TEAM_071");
                assertThat(statusOf(id)).isEqualTo("ACTIVE");
                assertThat(restrictions).as("承認が勝ったら制限は作られない").isZero();
            } else {
                assertThat(approved.status()).isEqualTo(404);
                assertThat(approved.errorCode()).isEqualTo("TEAM_070");
                assertThat(statusOf(id)).isNull();
                assertThat(restrictions).isEqualTo(1);
            }
        }
    }

    @Test
    @DisplayName("同じ申請を並行に2回承認すると、ちょうど一方だけが 200 で、もう一方は 409 TEAM_071（通知は1件）")
    void 二重承認はちょうど一方だけ成功する() throws Exception {
        Fixture fx = fixture();
        long id = inTx(() -> insertMembershipRow(fx.team().id(), fx.org().id(), "PENDING", "TEAM_APPLY", null,
                LocalDateTime.now()));

        List<Result> results = runConcurrently(List.of(
                () -> approve(fx.xa(), fx.org().slug(), id),
                () -> approve(fx.xa(), fx.org().slug(), id)));

        assertThat(results).extracting(Result::status).containsExactlyInAnyOrder(200, 409);
        assertThat(results.stream().filter(r -> r.status() == 409).findFirst().orElseThrow().errorCode())
                .isEqualTo("TEAM_071");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM audit_logs WHERE event_type = "
                + "'TEAM_ORG_MEMBERSHIP_CREATED' AND team_id = ?", Long.class, fx.team().id())).isEqualTo(1L);
        assertThat(jobIdsOf(id, "TEAM_ORG_APPLICATION_APPROVED")).hasSize(1);
    }

    // =====================================================================
    // AC-C01・G117b・P07 承認の通知
    // =====================================================================

    @Test
    @DisplayName("AC-C01・G117b・P07 承認の通知はチームの加盟操作者（TA・TG）にだけ届き、TM・TD・組織 ADMIN には届かない")
    void 承認の通知は加盟操作者にだけ届く() throws Exception {
        Fixture fx = fixture();
        UUID group = inTx(() -> newGroup(fx.org().id(), "北地区", false));
        long id = inTx(() -> insertMembershipRow(fx.team().id(), fx.org().id(), "PENDING", "TEAM_APPLY", group,
                LocalDateTime.now()));

        assertThat(approve(fx.xa(), fx.org().slug(), id).status()).isEqualTo(200);

        UUID jobId = singleJob(id, "TEAM_ORG_APPLICATION_APPROVED");
        Map<String, Object> job = jobRow(jobId);
        assertThat(job.get("scope_type")).isEqualTo("TEAM_AFFILIATION_OPS");
        assertThat(job.get("scope_ref")).isEqualTo(String.valueOf(fx.team().id()));
        assertThat(job.get("action_url")).isEqualTo("/teams/" + fx.team().slug() + "/affiliations");
        assertThat(((Number) job.get("organization_id")).longValue()).isEqualTo(fx.org().id());

        worker.processOne(jobRepository.findById(jobId).orElseThrow());
        assertThat(jobRepository.findById(jobId).orElseThrow().getStatus())
                .isEqualTo(NotificationFanoutJobStatus.DONE);

        List<Map<String, Object>> rows = notificationsOf("TEAM_ORG_APPLICATION_APPROVED", fx);
        assertThat(rows).extracting(r -> ((Number) r.get("user_id")).longValue())
                .as("TA と TG にだけ1件ずつ届く（TM・TD・XA には届かない）")
                .containsExactlyInAnyOrder(fx.ta(), fx.tg());
        assertThat(rows).extracting(r -> r.get("action_url"))
                .containsOnly("/teams/" + fx.team().slug() + "/affiliations");
        assertThat(rows).extracting(r -> r.get("source_type")).containsOnly("TEAM_ORG_MEMBERSHIP");
        assertThat(rows).extracting(r -> String.valueOf(r.get("body")))
                .allSatisfy(body -> assertThat(body).contains(fx.org().name()).contains("北地区"));
    }

    // =====================================================================
    // AC-C07・G117c・P07 拒否の通知
    // =====================================================================

    @Test
    @DisplayName("AC-C07・G117c・P07 拒否の通知は TA・TG にだけ届き、?view=applications を開く。理由を付けたときだけ本文に理由が入る")
    void 拒否の通知は理由の有無で本文が変わる() throws Exception {
        Fixture fx = fixture();
        OrgFx otherOrg = inTx(this::newOrg);
        long admin2 = inTx(() -> {
            long u = newUser();
            makeOrgAdmin(u, otherOrg.id());
            return u;
        });
        long withReason = inTx(() -> insertMembershipRow(fx.team().id(), fx.org().id(), "PENDING", "TEAM_APPLY",
                null, LocalDateTime.now()));
        long withoutReason = inTx(() -> insertMembershipRow(fx.team().id(), otherOrg.id(), "PENDING", "TEAM_APPLY",
                null, LocalDateTime.now()));

        assertThat(reject(fx.xa(), fx.org().slug(), withReason, "今年度の募集は終了しました").status()).isEqualTo(200);
        assertThat(reject(admin2, otherOrg.slug(), withoutReason, null).status()).isEqualTo(200);

        for (long id : List.of(withReason, withoutReason)) {
            UUID jobId = singleJob(id, "TEAM_ORG_APPLICATION_REJECTED");
            Map<String, Object> job = jobRow(jobId);
            assertThat(job.get("scope_type")).isEqualTo("TEAM_AFFILIATION_OPS");
            assertThat(job.get("scope_ref")).isEqualTo(String.valueOf(fx.team().id()));
            worker.processOne(jobRepository.findById(jobId).orElseThrow());
        }

        List<Map<String, Object>> rows = notificationsOf("TEAM_ORG_APPLICATION_REJECTED", fx);
        assertThat(rows).extracting(r -> ((Number) r.get("user_id")).longValue())
                .as("2件の拒否それぞれが TA と TG にだけ届く")
                .containsExactlyInAnyOrder(fx.ta(), fx.tg(), fx.ta(), fx.tg());
        assertThat(rows).extracting(r -> r.get("action_url"))
                .containsOnly("/teams/" + fx.team().slug() + "/affiliations?view=applications");
        List<String> bodies = new ArrayList<>();
        rows.forEach(r -> bodies.add(String.valueOf(r.get("body"))));
        assertThat(bodies.stream().filter(b -> b.contains("今年度の募集は終了しました")).count())
                .as("理由入りの本文は理由を付けた拒否の2件だけ").isEqualTo(2);
        assertThat(bodies.stream().filter(b -> b.contains(otherOrg.name())).toList())
                .hasSize(2)
                .allSatisfy(b -> assertThat(b).doesNotContain("今年度の募集は終了しました"));
    }

    // =====================================================================
    // ヘルパー
    // =====================================================================

    /** 1組のフィクスチャ（チームT・組織X と人物）。 */
    private record Fixture(TeamFx team, OrgFx org, long ta, long tg, long tm, long td, long xa) {
    }

    private Fixture fixture() {
        TeamFx team = inTx(this::newTeam);
        OrgFx org = inTx(() -> newOrg(true, true, "OPTIONAL", "PUBLIC"));
        long[] users = inTx(() -> {
            seedAffiliationPermission();
            long ta = newUser();
            long tg = newUser();
            long tm = newUser();
            long td = newUser();
            long xa = newUser();
            makeTeamAdmin(ta, team.id());
            makeTeamMember(tg, team.id());
            grantAffiliationByPermissionGroup(tg, team.id(), "MEMBER");
            makeTeamMember(tm, team.id());
            makeTeamDeputy(td, team.id());
            makeOrgAdmin(xa, org.id());
            return new long[]{ta, tg, tm, td, xa};
        });
        return new Fixture(team, org, users[0], users[1], users[2], users[3], users[4]);
    }

    /** API の結果（HTTP ステータス・エラーコード）。 */
    private record Result(int status, String errorCode) {
    }

    private Result approve(long actor, String orgSlug, long membershipId) throws Exception {
        return toResult(mockMvc.perform(post("/api/v1/organizations/{slug}/team-applications/{id}/approve",
                        orgSlug, membershipId)
                .with(user(String.valueOf(actor)))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"overrideGroup\":false}")).andReturn());
    }

    private Result reject(long actor, String orgSlug, long membershipId) throws Exception {
        return reject(actor, orgSlug, membershipId, null);
    }

    private Result reject(long actor, String orgSlug, long membershipId, String reason) throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("reason", reason);
        body.put("block", false);
        return toResult(mockMvc.perform(post("/api/v1/organizations/{slug}/team-applications/{id}/reject",
                        orgSlug, membershipId)
                .with(user(String.valueOf(actor)))
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(body))).andReturn());
    }

    private Result withdraw(long actor, String teamSlug, long membershipId) throws Exception {
        return toResult(mockMvc.perform(delete("/api/v1/teams/{teamSlug}/org-applications/{id}",
                        teamSlug, membershipId)
                .with(user(String.valueOf(actor)))).andReturn());
    }

    private Result toResult(MvcResult result) throws Exception {
        int status = result.getResponse().getStatus();
        String content = result.getResponse().getContentAsString();
        if (content == null || content.isBlank()) {
            return new Result(status, null);
        }
        JsonNode body = objectMapper.readTree(content);
        return new Result(status, body.path("error").path("code").asText(null));
    }

    /** 全タスクを同時にスタートさせ、結果を投入順に返す。 */
    private List<Result> runConcurrently(List<Callable<Result>> tasks) throws Exception {
        ExecutorService executor = Executors.newFixedThreadPool(tasks.size());
        try {
            CountDownLatch ready = new CountDownLatch(tasks.size());
            CountDownLatch start = new CountDownLatch(1);
            List<Future<Result>> futures = new ArrayList<>();
            for (Callable<Result> task : tasks) {
                futures.add(executor.submit(() -> {
                    ready.countDown();
                    start.await();
                    return task.call();
                }));
            }
            assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
            start.countDown();
            List<Result> results = new ArrayList<>();
            for (Future<Result> future : futures) {
                results.add(future.get(60, TimeUnit.SECONDS));
            }
            return results;
        } finally {
            executor.shutdownNow();
        }
    }

    /** フィクスチャ作りをトランザクションに包んでコミットする。 */
    private <T> T inTx(Supplier<T> action) {
        return new TransactionTemplate(transactionManager).execute(status -> action.get());
    }

    private String statusOf(long membershipId) {
        List<String> rows = jdbc.queryForList(
                "SELECT status FROM team_org_memberships WHERE id = ?", String.class, membershipId);
        return rows.isEmpty() ? null : rows.get(0);
    }

    private long restrictionCount(long teamId, long orgId) {
        Long count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM team_org_affiliation_restrictions WHERE team_id = ? AND organization_id = ?",
                Long.class, teamId, orgId);
        return count == null ? 0 : count;
    }

    private List<UUID> jobIdsOf(long membershipId, String notificationType) {
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

    private UUID singleJob(long membershipId, String notificationType) {
        List<UUID> ids = jobIdsOf(membershipId, notificationType);
        assertThat(ids).as("%s の通知ジョブは1件", notificationType).hasSize(1);
        return ids.get(0);
    }

    private Map<String, Object> jobRow(UUID jobId) {
        NotificationFanoutJob job = jobRepository.findById(jobId).orElseThrow();
        assertThat(job).isNotNull();
        return jdbc.queryForMap(
                "SELECT scope_type, scope_ref, action_url, organization_id FROM notification_fanout_jobs WHERE id = ?",
                uuidBytes(jobId));
    }

    private List<Map<String, Object>> notificationsOf(String type, Fixture fx) {
        return jdbc.queryForList(
                "SELECT user_id, action_url, source_type, body FROM notifications WHERE notification_type = ? "
                        + "AND user_id IN (?, ?, ?, ?, ?)",
                type, fx.ta(), fx.tg(), fx.tm(), fx.td(), fx.xa());
    }

    private static byte[] uuidBytes(UUID id) {
        ByteBuffer buffer = ByteBuffer.allocate(16);
        buffer.putLong(id.getMostSignificantBits());
        buffer.putLong(id.getLeastSignificantBits());
        return buffer.array();
    }
}
