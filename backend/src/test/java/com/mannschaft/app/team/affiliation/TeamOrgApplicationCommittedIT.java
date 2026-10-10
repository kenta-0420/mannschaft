package com.mannschaft.app.team.affiliation;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.mannschaft.app.notification.fanout.NotificationFanoutJob;
import com.mannschaft.app.notification.fanout.NotificationFanoutJobRepository;
import com.mannschaft.app.notification.fanout.NotificationFanoutJobStatus;
import com.mannschaft.app.notification.fanout.NotificationFanoutWorker;
import com.mannschaft.app.notification.outbox.NotificationOutboxRelay;
import com.mannschaft.app.team.entity.TeamOrgAffiliationDirection;
import com.mannschaft.app.team.entity.TeamOrgAffiliationRestrictionKind;
import com.mannschaft.app.team.entity.TeamOrgAffiliationRestrictionReason;
import com.mannschaft.app.team.service.TeamOrgAffiliationRestrictionService;
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
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * F01.2.1 部隊 2-B1 — コミットを伴う検証（並行・通知の配信・通知の失敗）の統合テスト（試練）。
 *
 * <p>{@link TeamOrgApplicationIT} は {@code @Transactional} でロールバックするため、トランザクションをまたぐ
 * 競合・ロック待ち・Worker による配信は検証できない。本クラスは<b>テストメソッドにトランザクションを張らず</b>、
 * フィクスチャはトランザクションを分けてコミットし、{@link #cleanUp()} で物理削除する。</p>
 *
 * <h2>検証すること</h2>
 * <ul>
 *   <li>AC-B07: 同じ組み合わせへの同時申請は、行が1件だけ（ちょうど一方が 201、もう一方が 409 TEAM_066）</li>
 *   <li>AC-B17: PENDING 9件から並行して2件申請すると、ちょうど1件だけが 201。10件から並行して2件なら両方 422
 *       （チーム行のロックで件数の確認と INSERT を直列化する）</li>
 *   <li>AC-G135(制限): 制限の UPSERT を並行に実行しても1行に収束する（BLOCK は COOLDOWN に負けない）</li>
 *   <li>AC-B01 / AC-G117a: 申請の通知ジョブを Worker が配信すると、組織 ADMIN にだけ届く</li>
 *   <li>AC-B18: 通知の配信が失敗しても、申請はロールバックされない</li>
 * </ul>
 *
 * <p>通知まわり（AC-B01・G117a・B18）は 6-D（通知の基盤）が main に着地するまで red になりうる。</p>
 */
@AutoConfigureMockMvc
@EnabledIf("com.mannschaft.app.support.test.AbstractMySqlIntegrationTest#isDockerAvailable")
@DisplayName("F01.2.1 2-B1 並行・通知の配信（コミットを伴う検証）")
class TeamOrgApplicationCommittedIT extends TeamAffiliationItSupport {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private PlatformTransactionManager transactionManager;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private TeamOrgAffiliationRestrictionService restrictionService;

    @Autowired
    private NotificationFanoutJobRepository jobRepository;

    @Autowired
    private NotificationFanoutWorker worker;

    /** 通知 outbox の relay（OB21: 試験プロファイルでは起こしを止めてあるので、ジョブを見る前に drain を挟む）。 */
    @Autowired
    private NotificationOutboxRelay relay;

    @AfterEach
    void cleanUp() {
        deleteCommittedFixtures(jdbc);
    }

    // =====================================================================
    // AC-B07 同時に2件送っても行は1件だけ
    // =====================================================================

    @Test
    @DisplayName("AC-B07 同じ組織への申請を同時に2件送ると、ちょうど一方が 201・もう一方が 409 TEAM_066 で、行は1件だけ")
    void 同時の二重申請は行が1件だけ() throws Exception {
        TeamFx team = inTx(this::newTeam);
        OrgFx org = inTx(this::newOrg);
        long admin = inTx(() -> {
            seedAffiliationPermission();
            long u = newUser();
            makeTeamAdmin(u, team.id());
            return u;
        });

        List<Result> results = runConcurrently(List.of(
                () -> apply(admin, team.slug(), org.slug()),
                () -> apply(admin, team.slug(), org.slug())));

        assertThat(results).extracting(Result::status).containsExactlyInAnyOrder(201, 409);
        assertThat(results.stream().filter(r -> r.status() == 409).findFirst().orElseThrow().errorCode())
                .isEqualTo("TEAM_066");
        assertThat(membershipCount(team.id(), org.id())).as("行は1件だけ").isEqualTo(1);
    }

    // =====================================================================
    // AC-B17 同時申請の上限は並行でも守られる（チーム行のロック）
    // =====================================================================

    @Test
    @DisplayName("AC-B17 PENDING 9件から別々の組織へ並行して2件申請すると、ちょうど1件だけが 201（もう一方は 422 TEAM_069）")
    void 九件から並行二件はちょうど一件だけ成功する() throws Exception {
        TeamFx team = inTx(this::newTeam);
        long admin = inTx(() -> {
            seedAffiliationPermission();
            long u = newUser();
            makeTeamAdmin(u, team.id());
            return u;
        });
        inTx(() -> {
            for (int i = 0; i < 9; i++) {
                insertMembershipRow(team.id(), newOrg().id(), "PENDING", "TEAM_APPLY", null, LocalDateTime.now());
            }
            return null;
        });
        OrgFx tenth = inTx(this::newOrg);
        OrgFx eleventh = inTx(this::newOrg);

        List<Result> results = runConcurrently(List.of(
                () -> apply(admin, team.slug(), tenth.slug()),
                () -> apply(admin, team.slug(), eleventh.slug())));

        assertThat(results).extracting(Result::status).containsExactlyInAnyOrder(201, 422);
        assertThat(results.stream().filter(r -> r.status() == 422).findFirst().orElseThrow().errorCode())
                .isEqualTo("TEAM_069");
        assertThat(pendingApplicationCount(team.id())).as("上限の10件を超えない").isEqualTo(10);
    }

    @Test
    @DisplayName("AC-B17 PENDING 10件から別々の組織へ並行して2件申請すると、両方 422 TEAM_069 で、件数は10件のまま")
    void 十件から並行二件は両方失敗する() throws Exception {
        TeamFx team = inTx(this::newTeam);
        long admin = inTx(() -> {
            seedAffiliationPermission();
            long u = newUser();
            makeTeamAdmin(u, team.id());
            return u;
        });
        inTx(() -> {
            for (int i = 0; i < 10; i++) {
                insertMembershipRow(team.id(), newOrg().id(), "PENDING", "TEAM_APPLY", null, LocalDateTime.now());
            }
            return null;
        });
        OrgFx a = inTx(this::newOrg);
        OrgFx b = inTx(this::newOrg);

        List<Result> results = runConcurrently(List.of(
                () -> apply(admin, team.slug(), a.slug()),
                () -> apply(admin, team.slug(), b.slug())));

        assertThat(results).extracting(Result::status).containsExactly(422, 422);
        assertThat(pendingApplicationCount(team.id())).isEqualTo(10);
    }

    // =====================================================================
    // AC-G135(制限) 制限の UPSERT は並行でも1行に収束する
    // =====================================================================

    @Test
    @DisplayName("AC-G135 制限の UPSERT を並行に実行しても1行に収束し、例外にならない（BLOCK は COOLDOWN に負けない）")
    void 制限のUPSERTは並行でも1行に収束する() throws Exception {
        TeamFx team = inTx(this::newTeam);
        OrgFx org = inTx(this::newOrg);
        long operator = inTx(this::newUser);

        List<Callable<Result>> tasks = new ArrayList<>();
        for (int i = 0; i < 6; i++) {
            tasks.add(() -> {
                inTx(() -> {
                    restrictionService.record(org.id(), team.id(), TeamOrgAffiliationDirection.TEAM_APPLY,
                            TeamOrgAffiliationRestrictionReason.WITHDRAWN,
                            TeamOrgAffiliationRestrictionKind.COOLDOWN, Duration.ofHours(24), operator);
                    return null;
                });
                return new Result(0, null);
            });
        }
        tasks.add(() -> {
            inTx(() -> {
                restrictionService.record(org.id(), team.id(), TeamOrgAffiliationDirection.TEAM_APPLY,
                        TeamOrgAffiliationRestrictionReason.REJECTED,
                        TeamOrgAffiliationRestrictionKind.BLOCK, null, operator);
                return null;
            });
            return new Result(0, null);
        });

        runConcurrently(tasks);

        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT kind, restricted_until FROM team_org_affiliation_restrictions "
                        + "WHERE organization_id = ? AND team_id = ? AND direction = 'TEAM_APPLY'",
                org.id(), team.id());
        assertThat(rows).as("UNIQUE 制約により1行に収束する").hasSize(1);
        assertThat(rows.get(0).get("kind")).as("BLOCK は COOLDOWN の記録順に関わらず残る").isEqualTo("BLOCK");
        assertThat(rows.get(0).get("restricted_until")).isNull();
    }

    // =====================================================================
    // AC-B01 / AC-G117a 通知の配信
    // =====================================================================

    @Test
    @DisplayName("AC-B01・G117a 申請の通知ジョブを Worker が配信すると、組織 ADMIN 全員にだけ action_url 付きで1件ずつ届く")
    void 通知は組織ADMINにだけ届く() throws Exception {
        TeamFx team = inTx(this::newTeam);
        OrgFx org = inTx(this::newOrg);
        long[] users = inTx(() -> {
            seedAffiliationPermission();
            long teamAdmin = newUser();
            long orgAdmin1 = newUser();
            long orgAdmin2 = newUser();
            long orgDeputy = newUser();
            makeTeamAdmin(teamAdmin, team.id());
            makeOrgAdmin(orgAdmin1, org.id());
            makeOrgAdmin(orgAdmin2, org.id());
            MembershipTestHelperAccess.insertOrgDeputy(em, orgDeputy, org.id());
            return new long[]{teamAdmin, orgAdmin1, orgAdmin2, orgDeputy};
        });

        Result created = apply(users[0], team.slug(), org.slug());
        assertThat(created.status()).isEqualTo(201);
        long membershipId = created.id();
        relay.drainAll(); // OB21: outbox → fan-out ジョブ

        UUID jobId = jobIdOf(membershipId);
        NotificationFanoutJob job = jobRepository.findById(jobId).orElseThrow();
        worker.processOne(job);

        assertThat(jobRepository.findById(jobId).orElseThrow().getStatus())
                .isEqualTo(NotificationFanoutJobStatus.DONE);
        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT user_id, action_url FROM notifications WHERE notification_type = "
                        + "'TEAM_ORG_APPLICATION_RECEIVED' AND user_id IN (?, ?, ?, ?) ORDER BY user_id",
                users[0], users[1], users[2], users[3]);
        assertThat(rows).extracting(r -> ((Number) r.get("user_id")).longValue())
                .as("組織 ADMIN の2名にだけ1件ずつ届く（申請したチーム ADMIN・組織 DEPUTY には届かない）")
                .containsExactly(Math.min(users[1], users[2]), Math.max(users[1], users[2]));
        assertThat(rows).extracting(r -> r.get("action_url"))
                .containsOnly("/organizations/" + org.slug() + "/member-teams?view=applications");
    }

    // =====================================================================
    // AC-B18 通知の配信が失敗しても申請はロールバックされない
    // =====================================================================

    @Test
    @DisplayName("AC-B18 通知の配信が失敗しても、申請は PENDING のまま残り、一覧にも出る（通知は enqueue するだけ）")
    void 通知の配信が失敗しても申請は残る() throws Exception {
        TeamFx team = inTx(this::newTeam);
        OrgFx org = inTx(this::newOrg);
        long admin = inTx(() -> {
            seedAffiliationPermission();
            long u = newUser();
            makeTeamAdmin(u, team.id());
            return u;
        });
        Result created = apply(admin, team.slug(), org.slug());
        assertThat(created.status()).isEqualTo(201);
        relay.drainAll(); // OB21: outbox → fan-out ジョブ
        UUID jobId = jobIdOf(created.id());

        // 配信を必ず失敗させる（受信者の解決キーが数値として読めない）。申請とは別のトランザクションで起きる失敗
        jdbc.update("UPDATE notification_fanout_jobs SET scope_ref = 'not-a-number' WHERE id = ?", uuidBytes(jobId));
        worker.processOne(jobRepository.findById(jobId).orElseThrow());

        assertThat(jobRepository.findById(jobId).orElseThrow().getStatus())
                .as("配信は失敗している（DONE ではない）").isNotEqualTo(NotificationFanoutJobStatus.DONE);
        assertThat(membershipCount(team.id(), org.id())).as("申請はロールバックされず残る").isEqualTo(1);
        MvcResult list = mockMvc.perform(get("/api/v1/teams/{slug}/org-applications", team.slug())
                .with(user(String.valueOf(admin)))).andReturn();
        assertThat(objectMapper.readTree(list.getResponse().getContentAsString()).get("data")).hasSize(1);
    }

    // =====================================================================
    // ヘルパー
    // =====================================================================

    /** 申請の結果（HTTP ステータス・エラーコード・作成された加盟 ID）。 */
    private record Result(int status, String errorCode, long id) {
        Result(int status, String errorCode) {
            this(status, errorCode, 0L);
        }
    }

    private Result apply(long actor, String teamSlug, String organizationSlug) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/v1/teams/{teamSlug}/org-applications", teamSlug)
                        .with(user(String.valueOf(actor)))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("organizationSlug", organizationSlug))))
                .andReturn();
        int status = result.getResponse().getStatus();
        JsonNode body = objectMapper.readTree(result.getResponse().getContentAsString());
        if (status == 201) {
            return new Result(status, null, body.get("data").get("id").asLong());
        }
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
    private <T> T inTx(java.util.function.Supplier<T> action) {
        return new TransactionTemplate(transactionManager).execute(status -> action.get());
    }

    private long membershipCount(long teamId, long orgId) {
        Long count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM team_org_memberships WHERE team_id = ? AND organization_id = ?",
                Long.class, teamId, orgId);
        return count == null ? 0 : count;
    }

    private long pendingApplicationCount(long teamId) {
        Long count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM team_org_memberships WHERE team_id = ? AND status = 'PENDING' "
                        + "AND direction = 'TEAM_APPLY'", Long.class, teamId);
        return count == null ? 0 : count;
    }

    private UUID jobIdOf(long membershipId) {
        byte[] raw = jdbc.queryForObject(
                "SELECT id FROM notification_fanout_jobs WHERE source_type = 'TEAM_ORG_MEMBERSHIP' "
                        + "AND source_id = ?", byte[].class, membershipId);
        ByteBuffer buffer = ByteBuffer.wrap(raw);
        return new UUID(buffer.getLong(), buffer.getLong());
    }

    private static byte[] uuidBytes(UUID id) {
        ByteBuffer buffer = ByteBuffer.allocate(16);
        buffer.putLong(id.getMostSignificantBits());
        buffer.putLong(id.getLeastSignificantBits());
        return buffer.array();
    }

    /** 組織 DEPUTY_ADMIN（通知を受けないことの確認用）。 */
    private static final class MembershipTestHelperAccess {
        static void insertOrgDeputy(jakarta.persistence.EntityManager em, long userId, long orgId) {
            com.mannschaft.app.support.test.MembershipTestHelper.insertMembership(em, userId,
                    com.mannschaft.app.membership.domain.ScopeType.ORGANIZATION, orgId,
                    com.mannschaft.app.membership.domain.RoleKind.MEMBER);
            com.mannschaft.app.support.test.MembershipTestHelper.insertUserRole(em, userId, "DEPUTY_ADMIN",
                    null, orgId);
        }
    }
}
