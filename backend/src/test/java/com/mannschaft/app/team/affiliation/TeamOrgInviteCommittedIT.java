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
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import com.mannschaft.app.team.service.FanoutTeamAffiliationNotifier;

import java.nio.ByteBuffer;
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
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * F01.2.1 部隊 2-C — コミットを伴う検証（通知の配信・並行競合）の統合テスト（試練）。
 *
 * <p>{@link TeamOrgInviteIT} は {@code @Transactional} でロールバックするため、トランザクションをまたぐ
 * 競合・ロック待ち・Worker による配信は検証できない。本クラスは<b>テストメソッドにトランザクションを張らず</b>、
 * フィクスチャはトランザクションを分けてコミットし、{@link #cleanUp()} で物理削除する。</p>
 *
 * <h2>検証すること</h2>
 * <ul>
 *   <li>AC-D01・G117d・P07: 招待の通知は、チームの加盟操作者（TA・TG）にだけ届き、TM・TD・組織 ADMIN には届かない</li>
 *   <li>AC-G117e: 承諾の通知は、組織 ADMIN 全員にだけ届く</li>
 *   <li>並行: 同じ (チーム, 組織) への招待と申請が同時に来ても、行は1件だけ（チーム行 → 組織行の固定順ロック）</li>
 *   <li>並行: 承諾と取消が同時に来ても、ちょうど一方だけが成功し、判定表どおりの応答になる</li>
 * </ul>
 */
@AutoConfigureMockMvc
@EnabledIf("com.mannschaft.app.support.test.AbstractMySqlIntegrationTest#isDockerAvailable")
@DisplayName("F01.2.1 2-C 招待の通知の配信・並行競合（コミットを伴う検証）")
class TeamOrgInviteCommittedIT extends TeamOrgInviteItSupport {

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

    /** 通知の登録失敗を起こすために差し替える（既定は実物を呼ぶ）。 */
    @MockitoSpyBean
    private FanoutTeamAffiliationNotifier notifier;

    @AfterEach
    void cleanUp() {
        deleteCommittedFixtures(jdbc);
    }

    // =====================================================================
    // AC-D01・G117d・P07 招待の通知はチームの加盟操作者にだけ届く
    // =====================================================================

    @Test
    @DisplayName("AC-G117d・P07 招待の通知を Worker が配信すると、TA・TG にだけ ?view=invites 付きで1件ずつ届き、TM・TD・XA には届かない")
    void 招待の通知はチームの加盟操作者にだけ届く() throws Exception {
        TeamFx team = inTx(this::newTeam);
        OrgFx org = inTx(this::newOrg);
        long[] users = inTx(() -> {
            seedAffiliationPermission();
            long xa = newUser();
            long ta = newUser();
            long td = newUser();
            long tm = newUser();
            long tg = newUser();
            makeOrgAdmin(xa, org.id());
            makeTeamAdmin(ta, team.id());
            makeTeamDeputy(td, team.id());
            makeTeamMember(tm, team.id());
            makeTeamMember(tg, team.id());
            grantAffiliationByPermissionGroup(tg, team.id(), "MEMBER");
            return new long[]{xa, ta, td, tm, tg};
        });

        Result invited = invite(users[0], org.slug(), team.slug());
        assertThat(invited.status()).isEqualTo(201);

        UUID jobId = jobIdOf(invited.id(), "TEAM_ORG_INVITE_RECEIVED");
        NotificationFanoutJob job = jobRepository.findById(jobId).orElseThrow();
        assertThat(job.getSourceType()).isEqualTo("TEAM_ORG_MEMBERSHIP");
        Map<String, Object> jobRow = jobRow(jobId);
        assertThat(jobRow.get("scope_type")).as("受信者はチームの加盟操作者").isEqualTo("TEAM_AFFILIATION_OPS");
        assertThat(jobRow.get("scope_ref")).as("受信者を引く対象はチーム").isEqualTo(String.valueOf(team.id()));
        assertThat(((Number) jobRow.get("organization_id")).longValue()).isEqualTo(org.id());
        assertThat(((Number) jobRow.get("source_id")).longValue()).isEqualTo(invited.id());
        worker.processOne(job);
        assertThat(jobRepository.findById(jobId).orElseThrow().getStatus())
                .isEqualTo(NotificationFanoutJobStatus.DONE);

        List<Map<String, Object>> rows = notificationRows("TEAM_ORG_INVITE_RECEIVED", users);
        assertThat(rows).extracting(r -> ((Number) r.get("user_id")).longValue())
                .as("TA と TG にだけ1件ずつ届く（TM・TD・XA には届かない）")
                .containsExactlyInAnyOrder(users[1], users[4]);
        assertThat(rows).extracting(r -> r.get("action_url"))
                .containsOnly("/teams/" + team.slug() + "/affiliations?view=invites");
    }

    // =====================================================================
    // AC-G117e 承諾の通知は組織 ADMIN にだけ届く
    // =====================================================================

    @Test
    @DisplayName("AC-G117e 承諾の通知を Worker が配信すると、組織 ADMIN 全員にだけ member-teams へのリンク付きで届く")
    void 承諾の通知は組織ADMINにだけ届く() throws Exception {
        TeamFx team = inTx(this::newTeam);
        OrgFx org = inTx(this::newOrg);
        long[] users = inTx(() -> {
            seedAffiliationPermission();
            long xa1 = newUser();
            long xa2 = newUser();
            long xd = newUser();
            long ta = newUser();
            makeOrgAdmin(xa1, org.id());
            makeOrgAdmin(xa2, org.id());
            makeOrgDeputy(xd, org.id());
            makeTeamAdmin(ta, team.id());
            return new long[]{xa1, xa2, xd, ta};
        });
        Result invited = invite(users[0], org.slug(), team.slug());
        assertThat(invited.status()).isEqualTo(201);

        Result accepted = perform(post("/api/v1/teams/{teamSlug}/org-invites/{id}/accept", team.slug(), invited.id())
                .with(user(String.valueOf(users[3]))));
        assertThat(accepted.status()).isEqualTo(200);

        UUID jobId = jobIdOf(invited.id(), "TEAM_ORG_INVITE_ACCEPTED");
        Map<String, Object> jobRow = jobRow(jobId);
        assertThat(jobRow.get("scope_type")).as("受信者は組織 ADMIN").isEqualTo("ORGANIZATION_ADMINS");
        assertThat(jobRow.get("scope_ref")).as("受信者を引く対象は組織").isEqualTo(String.valueOf(org.id()));
        assertThat(((Number) jobRow.get("organization_id")).longValue()).isEqualTo(org.id());
        assertThat(((Number) jobRow.get("source_id")).longValue()).isEqualTo(invited.id());
        worker.processOne(jobRepository.findById(jobId).orElseThrow());

        List<Map<String, Object>> rows = notificationRows("TEAM_ORG_INVITE_ACCEPTED", users);
        assertThat(rows).extracting(r -> ((Number) r.get("user_id")).longValue())
                .as("組織 ADMIN の2名にだけ届く（組織 DEPUTY・承諾した TA には届かない）")
                .containsExactlyInAnyOrder(users[0], users[1]);
        assertThat(rows).extracting(r -> r.get("action_url"))
                .containsOnly("/organizations/" + org.slug() + "/member-teams");
    }

    // =====================================================================
    // 通知の登録が失敗しても、確定した操作と監査は残る（TX の分離の回帰防止）
    // =====================================================================

    @Test
    @DisplayName("通知の登録が失敗しても、招待の行と TEAM_ORG_INVITE_SENT の監査は残る（通知が書き込み・監査と別のトランザクションである証跡）")
    void 通知が失敗しても招待と監査は残る() throws Exception {
        TeamFx team = inTx(this::newTeam);
        OrgFx org = inTx(this::newOrg);
        long xa = inTx(() -> {
            seedAffiliationPermission();
            long id = newUser();
            makeOrgAdmin(id, org.id());
            return id;
        });
        doThrow(new IllegalStateException("通知の登録失敗（テスト）")).when(notifier).enqueueAfterCommit(any());

        try {
            perform(post("/api/v1/organizations/{slug}/team-invites", org.slug())
                    .with(user(String.valueOf(xa)))
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(objectMapper.writeValueAsString(Map.of("teamSlug", team.slug()))));
        } catch (Exception expected) {
            // 通知の失敗は呼び出し元へ伝わる（握り潰さない）。応答の形ではなく、残ったものを検証する
        }

        assertThat(membershipCount(team.id(), org.id())).as("招待の行は巻き戻らない").isEqualTo(1);
        Long audits = jdbc.queryForObject(
                "SELECT COUNT(*) FROM audit_logs WHERE event_type = 'TEAM_ORG_INVITE_SENT' AND team_id = ?",
                Long.class, team.id());
        assertThat(audits).as("通知より先に記録した監査は、通知が失敗しても残る").isEqualTo(1L);
    }

    // =====================================================================
    // 招待と申請の並行競合（チーム行 → 組織行の固定順ロック）
    // =====================================================================

    @Test
    @DisplayName("並行: 同じ (チーム, 組織) への招待と申請を同時に送ると、ちょうど一方だけが 201、もう一方は 409 TEAM_066 で、行は1件だけ")
    void 招待と申請の同時実行は一方だけ成功する() throws Exception {
        TeamFx team = inTx(this::newTeam);
        OrgFx org = inTx(this::newOrg);
        long[] users = inTx(() -> {
            seedAffiliationPermission();
            long xa = newUser();
            long ta = newUser();
            makeOrgAdmin(xa, org.id());
            makeTeamAdmin(ta, team.id());
            return new long[]{xa, ta};
        });

        for (int round = 0; round < 3; round++) {
            List<Result> results = runConcurrently(List.of(
                    () -> invite(users[0], org.slug(), team.slug()),
                    () -> perform(post("/api/v1/teams/{teamSlug}/org-applications", team.slug())
                            .with(user(String.valueOf(users[1])))
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(Map.of("organizationSlug", org.slug()))))));

            assertThat(results).extracting(Result::status).containsExactlyInAnyOrder(201, 409);
            assertThat(results.stream().filter(r -> r.status() == 409).findFirst().orElseThrow().errorCode())
                    .isEqualTo("TEAM_066");
            assertThat(membershipCount(team.id(), org.id())).as("行は1件だけ").isEqualTo(1);
            jdbc.update("DELETE FROM team_org_memberships WHERE team_id = ? AND organization_id = ?",
                    team.id(), org.id());
        }
    }

    // =====================================================================
    // 承諾と取消の並行競合（§6.4 の判定表）
    // =====================================================================

    @Test
    @DisplayName("並行: 承諾と取消を同時に送ると、ちょうど一方だけが成功する（承諾が先なら取消は 409 TEAM_071、取消が先なら承諾は 404 TEAM_070）")
    void 承諾と取消の同時実行は一方だけ成功する() throws Exception {
        TeamFx team = inTx(this::newTeam);
        OrgFx org = inTx(this::newOrg);
        long[] users = inTx(() -> {
            seedAffiliationPermission();
            long xa = newUser();
            long ta = newUser();
            makeOrgAdmin(xa, org.id());
            makeTeamAdmin(ta, team.id());
            return new long[]{xa, ta};
        });
        Result invited = invite(users[0], org.slug(), team.slug());
        assertThat(invited.status()).isEqualTo(201);

        List<Result> results = runConcurrently(List.of(
                () -> perform(post("/api/v1/teams/{teamSlug}/org-invites/{id}/accept", team.slug(), invited.id())
                        .with(user(String.valueOf(users[1])))),
                () -> perform(delete("/api/v1/organizations/{slug}/team-invites/{teamSlug}", org.slug(), team.slug())
                        .with(user(String.valueOf(users[0]))))));

        Result acceptResult = results.get(0);
        Result cancelResult = results.get(1);
        if (acceptResult.status() == 200) {
            assertThat(cancelResult.status()).isEqualTo(409);
            assertThat(cancelResult.errorCode()).isEqualTo("TEAM_071");
            assertThat(jdbc.queryForObject("SELECT status FROM team_org_memberships WHERE id = ?", String.class,
                    invited.id())).isEqualTo("ACTIVE");
        } else {
            assertThat(cancelResult.status()).isEqualTo(204);
            assertThat(acceptResult.status()).isEqualTo(404);
            assertThat(acceptResult.errorCode()).isEqualTo("TEAM_070");
            assertThat(membershipCount(team.id(), org.id())).isZero();
        }
    }

    // =====================================================================
    // ヘルパー
    // =====================================================================

    /** 結果（HTTP ステータス・エラーコード・作成された加盟 ID）。 */
    private record Result(int status, String errorCode, long id) {
    }

    private Result invite(long actor, String orgSlug, String teamSlug) throws Exception {
        return perform(post("/api/v1/organizations/{slug}/team-invites", orgSlug)
                .with(user(String.valueOf(actor)))
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(Map.of("teamSlug", teamSlug))));
    }

    private Result perform(MockHttpServletRequestBuilder request) throws Exception {
        MvcResult result = mockMvc.perform(request).andReturn();
        int status = result.getResponse().getStatus();
        String content = result.getResponse().getContentAsString();
        if (content.isBlank()) {
            return new Result(status, null, 0L);
        }
        JsonNode body = objectMapper.readTree(content);
        long id = body.path("data").path("id").asLong(0L);
        return new Result(status, body.path("error").path("code").asText(null), id);
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

    private List<Map<String, Object>> notificationRows(String type, long[] users) {
        String in = java.util.Arrays.stream(users).mapToObj(String::valueOf).collect(Collectors.joining(","));
        return jdbc.queryForList("SELECT user_id, action_url FROM notifications WHERE notification_type = ? "
                + "AND user_id IN (" + in + ") ORDER BY user_id", type);
    }

    /** fan-out ジョブ行の受信者の解決方式（scope_type / scope_ref）と出どころ。 */
    private Map<String, Object> jobRow(UUID jobId) {
        return jdbc.queryForMap(
                "SELECT scope_type, scope_ref, organization_id, source_id FROM notification_fanout_jobs WHERE id = ?",
                uuidBytes(jobId));
    }

    private static byte[] uuidBytes(UUID id) {
        ByteBuffer buffer = ByteBuffer.allocate(16);
        buffer.putLong(id.getMostSignificantBits());
        buffer.putLong(id.getLeastSignificantBits());
        return buffer.array();
    }

    private UUID jobIdOf(long membershipId, String notificationType) {
        byte[] raw = jdbc.queryForObject(
                "SELECT id FROM notification_fanout_jobs WHERE source_type = 'TEAM_ORG_MEMBERSHIP' "
                        + "AND source_id = ? AND notification_type = ?", byte[].class, membershipId, notificationType);
        ByteBuffer buffer = ByteBuffer.wrap(raw);
        return new UUID(buffer.getLong(), buffer.getLong());
    }
}
