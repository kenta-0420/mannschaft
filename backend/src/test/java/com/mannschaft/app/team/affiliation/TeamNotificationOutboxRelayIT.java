package com.mannschaft.app.team.affiliation;

import com.mannschaft.app.admin.batch.BatchEndpointRegistry;
import com.mannschaft.app.notification.NotificationType;
import com.mannschaft.app.notification.outbox.NotificationOutboxIngestService;
import com.mannschaft.app.notification.outbox.NotificationOutboxMessage;
import com.mannschaft.app.notification.outbox.NotificationOutboxPayload;
import com.mannschaft.app.notification.outbox.NotificationOutboxPayloadCodec;
import com.mannschaft.app.notification.outbox.NotificationOutboxRelay;
import com.mannschaft.app.notification.outbox.NotificationOutboxStuckRecoveryBatch;
import com.mannschaft.app.notification.outbox.NotificationOutboxSweepBatch;
import com.mannschaft.app.team.service.TeamNotificationOutboxSource;
import com.mannschaft.app.team.service.TeamOrgAffiliationCommandService;
import io.micrometer.core.instrument.Gauge;
import net.javacrumbs.shedlock.core.DefaultLockingTaskExecutor;
import net.javacrumbs.shedlock.core.LockConfiguration;
import net.javacrumbs.shedlock.core.LockingTaskExecutor;
import net.javacrumbs.shedlock.provider.jdbctemplate.JdbcTemplateLockProvider;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

/**
 * 通知 outbox P1 — relay（claim → 取り込み → 印付け・再試行・回収・掃除・メトリクス）の試練 IT。
 *
 * <p>AC（陣立て書 2026-10-10-notification-outbox-gungi.md §5 P1 と改訂第2版）:
 * OB03・OB04・OB06・OB06a・OB07・OB08・OB08b・OB09・OB11・OB12a・OB12b・OB17・OB19・OB20。</p>
 *
 * <p>取り込みは {@link #drain()}（{@link NotificationOutboxRelay#drainAll()} の同期呼び出し）でだけ起きる
 * （試験プロファイルで起こしを止め、予備ポーラーもスケジュールされない）。</p>
 */
@EnabledIf("com.mannschaft.app.support.test.AbstractMySqlIntegrationTest#isDockerAvailable")
@DisplayName("通知 outbox P1 relay（取り込み・再試行・回収・掃除）")
class TeamNotificationOutboxRelayIT extends TeamNotificationOutboxItSupport {

    @MockitoSpyBean
    private NotificationOutboxIngestService ingestSpy;

    @MockitoSpyBean
    private TeamNotificationOutboxSource sourceSpy;

    @MockitoSpyBean
    private NotificationOutboxPayloadCodec codecSpy;

    @Autowired
    private NotificationOutboxStuckRecoveryBatch recoveryBatch;

    @Autowired
    private NotificationOutboxSweepBatch sweepBatch;

    @Autowired
    private BatchEndpointRegistry batchEndpointRegistry;

    @Autowired
    private TeamOrgAffiliationCommandService commandService;

    // =====================================================================
    // OB03 取り込み
    // =====================================================================

    @Test
    @DisplayName("OB03 drain で ORGANIZATION_ADMINS のジョブが1件（冪等キーは F01.2.1:<type>:<membershipId>）と文面6行ができ、outbox は RELAYED と relayed_at")
    void ob03_drainでジョブ1件と文面6行ができoutboxはRELAYEDになる() {
        OrgFx org = inTx(this::newOrg);
        long membershipId = appendNotice(org);
        UUID key = idempotencyKeyOf(NotificationType.TEAM_ORG_APPLICATION_RECEIVED, membershipId);

        drain();

        assertThat(jobCount(key)).isEqualTo(1);
        Map<String, Object> job = jdbc.queryForMap(
                "SELECT scope_type, scope_ref, organization_id, source_type, source_id, notification_type, action_url "
                        + "FROM notification_fanout_jobs WHERE source_event_uuid = ?", uuidBytes(key));
        assertThat(job.get("scope_type")).isEqualTo("ORGANIZATION_ADMINS");
        assertThat(job.get("scope_ref")).isEqualTo(String.valueOf(org.id()));
        assertThat(((Number) job.get("organization_id")).longValue()).isEqualTo(org.id());
        assertThat(job.get("source_type")).isEqualTo("TEAM_ORG_MEMBERSHIP");
        assertThat(((Number) job.get("source_id")).longValue()).isEqualTo(membershipId);
        assertThat(job.get("notification_type")).isEqualTo("TEAM_ORG_APPLICATION_RECEIVED");
        assertThat(job.get("action_url")).isEqualTo("/organizations/" + org.slug() + "/member-teams?view=applications");
        assertThat(jobMessageCount(key)).as("6言語の文面").isEqualTo(6);

        Map<String, Object> row = outboxRow(key);
        assertThat(row.get("status")).isEqualTo("RELAYED");
        assertThat(row.get("relayed_at")).isNotNull();
    }

    // =====================================================================
    // OB04 取り込み後・印付け前に落ちる
    // =====================================================================

    @Test
    @DisplayName("OB04 取り込みのコミット後に印付けが失敗しても、回収 → 再 drain でジョブは1件・文面6行のまま、最終的に RELAYED")
    void ob04_印付け失敗から回収して再drainしてもジョブは1件() {
        OrgFx org = inTx(this::newOrg);
        long membershipId = appendNotice(org);
        UUID key = idempotencyKeyOf(NotificationType.TEAM_ORG_APPLICATION_RECEIVED, membershipId);
        doThrow(new IllegalStateException("印付けの失敗（テスト）")).doCallRealMethod()
                .when(sourceSpy).markRelayed(any(), any(), any());

        drain();
        assertThat(jobCount(key)).as("取り込みはコミット済み").isEqualTo(1);
        assertThat(statusOf(membershipId)).as("印付けに失敗したので RELAYING のまま").isEqualTo("RELAYING");

        jdbc.update("UPDATE team_notification_outbox SET claimed_at = UTC_TIMESTAMP(6) - INTERVAL 3 MINUTE "
                + "WHERE idempotency_key = ?", uuidBytes(key));
        recoveryBatch.recover();
        assertThat(statusOf(membershipId)).isEqualTo("PENDING");

        drain();
        assertThat(statusOf(membershipId)).isEqualTo("RELAYED");
        assertThat(jobCount(key)).as("二重取り込みでもジョブは1件").isEqualTo(1);
        assertThat(jobMessageCount(key)).isEqualTo(6);
    }

    // =====================================================================
    // OB06 / OB06a 取り込みの失敗
    // =====================================================================

    @Test
    @DisplayName("OB06 取り込みが失敗すると PENDING・attempt_count=1・next_attempt_at を先送り・last_error を記録し、申請の行は残る")
    void ob06_取り込み失敗で再試行待ちになり申請は残る() {
        TeamFx team = inTx(this::newTeam);
        OrgFx org = inTx(this::newOrg);
        long operator = inTx(this::newUser);
        long membershipId = inTx(() -> commandService.apply(team.id(), org.id(), operator, null, null).id());
        doThrow(new IllegalStateException("取り込みの失敗（テスト）")).when(ingestSpy).ingest(any());

        Instant before = dbNowUtc();
        drain();

        Map<String, Object> row = outboxRowOf(membershipId);
        assertThat(row.get("status")).isEqualTo("PENDING");
        assertThat(((Number) row.get("attempt_count")).intValue()).isEqualTo(1);
        assertThat(instantOf(row.get("next_attempt_at")))
                .as("30秒×2^0 の先送り").isAfterOrEqualTo(before.plusSeconds(25));
        assertThat((String) row.get("last_error")).contains("取り込みの失敗");
        assertThat(row.get("claim_token")).as("世代を手放す").isNull();
        assertThat(jobCountOf(membershipId)).isZero();
        Long memberships = jdbc.queryForObject("SELECT COUNT(*) FROM team_org_memberships WHERE id = ?",
                Long.class, membershipId);
        assertThat(memberships).as("取り込みの失敗で申請は巻き戻らない").isEqualTo(1L);
    }

    @Test
    @DisplayName("OB06a 1回の claim の3行のうち2行目だけ取り込みが失敗しても、1・3行目は RELAYED、2行目は PENDING")
    void ob06a_1行の失敗で残りの行を止めない() {
        OrgFx org = inTx(this::newOrg);
        long first = appendNotice(org);
        long second = appendNotice(org);
        long third = appendNotice(org);
        doAnswer(invocation -> {
            NotificationOutboxPayload payload = invocation.getArgument(0);
            if (Long.valueOf(second).equals(payload.fanout().sourceId())) {
                throw new IllegalStateException("2行目だけの失敗（テスト）");
            }
            return invocation.callRealMethod();
        }).when(ingestSpy).ingest(any());

        drain();

        assertThat(statusOf(first)).isEqualTo("RELAYED");
        assertThat(statusOf(second)).isEqualTo("PENDING");
        assertThat(attemptOf(second)).isEqualTo(1);
        assertThat(statusOf(third)).isEqualTo("RELAYED");
        assertThat(jobCountOf(first)).isEqualTo(1);
        assertThat(jobCountOf(second)).isZero();
        assertThat(jobCountOf(third)).isEqualTo(1);
    }

    // =====================================================================
    // OB07 DEAD
    // =====================================================================

    @Test
    @DisplayName("OB07 10回失敗すると DEAD と dead_at、メトリクス dead が +1、以後は claim されない")
    void ob07_10回の失敗でDEADになり以後claimされない() {
        OrgFx org = inTx(this::newOrg);
        long membershipId = appendNotice(org);
        doThrow(new IllegalStateException("恒久的な失敗（テスト）")).when(ingestSpy).ingest(any());
        double deadBefore = counterSum(NotificationOutboxRelay.METRIC_DEAD);

        for (int attempt = 1; attempt <= NotificationOutboxRelay.MAX_ATTEMPTS; attempt++) {
            makeDueNow(membershipId);
            drain();
            if (attempt == NotificationOutboxRelay.MAX_ATTEMPTS - 1) {
                assertThat(statusOf(membershipId)).as("9回目まではまだ再試行する").isEqualTo("PENDING");
            }
        }

        Map<String, Object> row = outboxRowOf(membershipId);
        assertThat(row.get("status")).isEqualTo("DEAD");
        assertThat(((Number) row.get("attempt_count")).intValue()).isEqualTo(10);
        assertThat(row.get("dead_at")).isNotNull();
        assertThat(counterSum(NotificationOutboxRelay.METRIC_DEAD) - deadBefore).isEqualTo(1.0);

        makeDueNow(membershipId);
        drain();
        verify(ingestSpy, times(10)).ingest(argThat(p -> p != null
                && Long.valueOf(membershipId).equals(p.fanout().sourceId())));
        assertThat(statusOf(membershipId)).as("DEAD は claim されない").isEqualTo("DEAD");
    }

    // =====================================================================
    // OB08 / OB08b 回収と世代
    // =====================================================================

    @Test
    @DisplayName("OB08 claimed_at が2分を超えた RELAYING は回収で PENDING・claim_token=NULL に戻り（recovered +1）、2分以内のものは戻らない。再 drain でジョブは1件")
    void ob08_古いRELAYINGは回収で戻り新しいものは戻らない() {
        OrgFx org = inTx(this::newOrg);
        long stale = appendNotice(org);
        long fresh = appendNotice(org);
        sourceSpy.claim(NotificationOutboxRelay.CLAIM_LIMIT, Instant.now());
        assertThat(statusOf(stale)).isEqualTo("RELAYING");
        assertThat(statusOf(fresh)).isEqualTo("RELAYING");
        jdbc.update("UPDATE team_notification_outbox SET claimed_at = UTC_TIMESTAMP(6) - INTERVAL 3 MINUTE "
                + "WHERE idempotency_key = ?", uuidBytes(idempotencyKeyOf(
                        NotificationType.TEAM_ORG_APPLICATION_RECEIVED, stale)));
        jdbc.update("UPDATE team_notification_outbox SET claimed_at = UTC_TIMESTAMP(6) - INTERVAL 60 SECOND "
                + "WHERE idempotency_key = ?", uuidBytes(idempotencyKeyOf(
                        NotificationType.TEAM_ORG_APPLICATION_RECEIVED, fresh)));
        double recoveredBefore = counterSum(NotificationOutboxRelay.METRIC_RECOVERED);

        recoveryBatch.recover();

        assertThat(statusOf(stale)).isEqualTo("PENDING");
        assertThat(outboxRowOf(stale).get("claim_token")).isNull();
        assertThat(statusOf(fresh)).as("2分以内の claim は取り込み中とみなす").isEqualTo("RELAYING");
        assertThat(counterSum(NotificationOutboxRelay.METRIC_RECOVERED) - recoveredBefore).isGreaterThanOrEqualTo(1.0);

        drain();
        assertThat(statusOf(stale)).isEqualTo("RELAYED");
        assertThat(jobCountOf(stale)).isEqualTo(1);
    }

    @Test
    @DisplayName("OB08b A が claim して止まり、回収後に B が claim し直して RELAYING の間に A が再開しても、A の古い世代の印付けは0行で B の claim_token・状態・attempt は変わらず、B は印付けでき、ジョブは1件")
    void ob08b_古い世代の印付けは当たらない() {
        OrgFx org = inTx(this::newOrg);
        long membershipId = appendNotice(org);
        UUID key = idempotencyKeyOf(NotificationType.TEAM_ORG_APPLICATION_RECEIVED, membershipId);
        UUID outboxId = uuidFromBytes(outboxRow(key).get("id"));

        // A が claim して止まる
        NotificationOutboxMessage claimedByA = claimOne(outboxId);
        jdbc.update("UPDATE team_notification_outbox SET claimed_at = UTC_TIMESTAMP(6) - INTERVAL 3 MINUTE "
                + "WHERE idempotency_key = ?", uuidBytes(key));
        recoveryBatch.recover();
        assertThat(statusOf(membershipId)).isEqualTo("PENDING");

        // B が claim し直し、取り込み中（RELAYING）のまま A が再開する。status 条件だけでは弾けない状態で世代を試す
        NotificationOutboxMessage claimedByB = claimOne(outboxId);
        assertThat(claimedByB.claimToken()).as("B は新しい世代").isNotEqualTo(claimedByA.claimToken());
        Map<String, Object> whileB = outboxRow(key);
        assertThat(whileB.get("status")).isEqualTo("RELAYING");

        // A の取り込みは冪等、A の印付けは古い世代なので当たらない
        ingestSpy.ingest(codecSpy.decode(claimedByA.payloadVersion(), claimedByA.payloadJson()));
        boolean markedByA = sourceSpy.markRelayed(outboxId, claimedByA.claimToken(), Instant.now());
        boolean failedByA = sourceSpy.markFailed(outboxId, claimedByA.claimToken(), "A の失敗",
                Instant.now().plusSeconds(30), false, Instant.now());
        boolean deadByA = sourceSpy.markFailed(outboxId, claimedByA.claimToken(), "A の失敗（DEAD）",
                Instant.now().plusSeconds(30), true, Instant.now());

        assertThat(markedByA).as("古い世代の markRelayed は0行").isFalse();
        assertThat(failedByA).as("古い世代の markFailed は0行").isFalse();
        assertThat(deadByA).as("古い世代の markFailed(DEAD) は0行").isFalse();
        Map<String, Object> afterA = outboxRow(key);
        assertThat(afterA.get("status")).as("B の RELAYING のまま").isEqualTo("RELAYING");
        assertThat(uuidFromBytes(afterA.get("claim_token"))).as("B の世代のまま").isEqualTo(claimedByB.claimToken());
        assertThat(((Number) afterA.get("attempt_count")).intValue()).isZero();
        assertThat(afterA.get("claimed_at")).isEqualTo(whileB.get("claimed_at"));
        assertThat(afterA.get("last_error")).isNull();
        assertThat(afterA.get("dead_at")).isNull();
        assertThat(afterA.get("relayed_at")).isNull();

        // B は自分の世代で印付けできる
        ingestSpy.ingest(codecSpy.decode(claimedByB.payloadVersion(), claimedByB.payloadJson()));
        assertThat(sourceSpy.markRelayed(outboxId, claimedByB.claimToken(), Instant.now())).isTrue();
        assertThat(statusOf(membershipId)).isEqualTo("RELAYED");
        assertThat(jobCount(key)).as("A・B の二重取り込みでもジョブは1件").isEqualTo(1);
    }

    @Test
    @DisplayName("OB08b relay が取り込んでいる間に回収され別の relay が claim し直していたら（RELAYING・別の claim_token）、印付けは0行で stale_mark が +1、別の relay の世代・状態・attempt は変わらない")
    void ob08b_relayの印付けが古い世代ならstale_markを数える() {
        OrgFx org = inTx(this::newOrg);
        long membershipId = appendNotice(org);
        UUID key = idempotencyKeyOf(NotificationType.TEAM_ORG_APPLICATION_RECEIVED, membershipId);
        UUID outboxId = uuidFromBytes(outboxRow(key).get("id"));
        UUID otherRelayToken = UUID.randomUUID();
        double staleBefore = counterSum(NotificationOutboxRelay.METRIC_STALE_MARK);
        doAnswer(invocation -> {
            // 取り込みの途中で「回収 → 別の relay が claim し直して取り込み中（RELAYING）」を起こす
            jdbc.update("UPDATE team_notification_outbox SET status = 'RELAYING', claimed_at = UTC_TIMESTAMP(6), "
                    + "claim_token = ? WHERE idempotency_key = ?", uuidBytes(otherRelayToken), uuidBytes(key));
            return invocation.callRealMethod();
        }).when(ingestSpy).ingest(any());

        drain();

        assertThat(counterSum(NotificationOutboxRelay.METRIC_STALE_MARK) - staleBefore).isEqualTo(1.0);
        Map<String, Object> row = outboxRow(key);
        assertThat(row.get("status")).as("別の relay の RELAYING のまま").isEqualTo("RELAYING");
        assertThat(uuidFromBytes(row.get("claim_token"))).isEqualTo(otherRelayToken);
        assertThat(((Number) row.get("attempt_count")).intValue()).isZero();
        assertThat(row.get("relayed_at")).isNull();
        assertThat(jobCount(key)).isEqualTo(1);

        // 別の relay は自分の世代で印付けできる
        assertThat(sourceSpy.markRelayed(outboxId, otherRelayToken, Instant.now())).isTrue();
        assertThat(statusOf(membershipId)).isEqualTo("RELAYED");
    }

    /** {@code outboxId} の行を claim する（他の行も claim されうるので該当行だけを返す）。 */
    private NotificationOutboxMessage claimOne(UUID outboxId) {
        return sourceSpy.claim(NotificationOutboxRelay.CLAIM_LIMIT, Instant.now())
                .stream().filter(m -> m.id().equals(outboxId)).findFirst().orElseThrow();
    }

    // =====================================================================
    // OB09 並行
    // =====================================================================

    @Test
    @DisplayName("OB09 2スレッドで同時に50行を drain すると、ジョブはちょうど50件で、全行が RELAYED")
    void ob09_2スレッドの同時drainでもジョブはちょうど50件() throws Exception {
        OrgFx org = inTx(this::newOrg);
        List<Long> memberships = new ArrayList<>();
        for (int i = 0; i < 50; i++) {
            memberships.add(appendNotice(org));
        }

        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            CountDownLatch start = new CountDownLatch(1);
            List<Future<Integer>> futures = new ArrayList<>();
            for (int i = 0; i < 2; i++) {
                futures.add(executor.submit(() -> {
                    start.await();
                    return drain();
                }));
            }
            start.countDown();
            for (Future<Integer> future : futures) {
                future.get(120, TimeUnit.SECONDS);
            }
        } finally {
            executor.shutdownNow();
        }

        assertThat(jobCountByOrganization(org.id())).isEqualTo(50);
        assertThat(memberships).allSatisfy(m -> assertThat(statusOf(m)).isEqualTo("RELAYED"));
    }

    // =====================================================================
    // OB17 テナントの取り違え
    // =====================================================================

    @Test
    @DisplayName("OB17 組織 A と B の行を混ぜて drain しても、各ジョブの organization_id と scope_ref は書いたときの組織のまま")
    void ob17_混在drainで組織を取り違えない() {
        OrgFx a = inTx(this::newOrg);
        OrgFx b = inTx(this::newOrg);
        List<long[]> written = new ArrayList<>(); // {membershipId, orgId}
        for (int i = 0; i < 3; i++) {
            written.add(new long[]{appendNotice(a), a.id()});
            written.add(new long[]{appendNotice(b), b.id()});
        }

        drain();

        for (long[] w : written) {
            Map<String, Object> job = jdbc.queryForMap(
                    "SELECT organization_id, scope_ref FROM notification_fanout_jobs WHERE source_event_uuid = ?",
                    uuidBytes(idempotencyKeyOf(NotificationType.TEAM_ORG_APPLICATION_RECEIVED, w[0])));
            assertThat(((Number) job.get("organization_id")).longValue()).isEqualTo(w[1]);
            assertThat(job.get("scope_ref")).isEqualTo(String.valueOf(w[1]));
        }
        assertThat(jobCountByOrganization(a.id())).isEqualTo(3);
        assertThat(jobCountByOrganization(b.id())).isEqualTo(3);
    }

    // =====================================================================
    // OB19 claim の境界
    // =====================================================================

    @Test
    @DisplayName("OB19 101行の PENDING から1回の claim は100行だけ取り、1回のポーラー実行では101行すべてが RELAYED になる")
    void ob19_claimは100行で1回のポーラー実行は101行を取り切る() {
        OrgFx org = inTx(this::newOrg);
        for (int i = 0; i < 101; i++) {
            appendNotice(org);
        }

        List<NotificationOutboxMessage> claimed = sourceSpy.claim(NotificationOutboxRelay.CLAIM_LIMIT, Instant.now());
        assertThat(claimed).as("1回の claim は最大100行").hasSize(100);
        Long relaying = jdbc.queryForObject("SELECT COUNT(*) FROM team_notification_outbox "
                + "WHERE organization_id = ? AND status = 'RELAYING'", Long.class, org.id());
        assertThat(relaying).isEqualTo(100L);

        jdbc.update("UPDATE team_notification_outbox SET status = 'PENDING', claim_token = NULL, claimed_at = NULL "
                + "WHERE organization_id = ?", org.id());
        relay.poll();

        Long relayed = jdbc.queryForObject("SELECT COUNT(*) FROM team_notification_outbox "
                + "WHERE organization_id = ? AND status = 'RELAYED'", Long.class, org.id());
        assertThat(relayed).as("100件の claim を空になるまで繰り返す").isEqualTo(101L);
        assertThat(jobCountByOrganization(org.id())).isEqualTo(101);
    }

    // =====================================================================
    // OB20 読めない版
    // =====================================================================

    @Test
    @DisplayName("OB20 読めない payload_version は失敗に数えず（attempt 0・DEAD にしない）60秒先送りし、reader を入れると取り込まれる")
    void ob20_読めない版は失敗に数えずreaderを入れると回復する() {
        OrgFx org = inTx(this::newOrg);
        long membershipId = appendNotice(org);
        UUID key = idempotencyKeyOf(NotificationType.TEAM_ORG_APPLICATION_RECEIVED, membershipId);
        jdbc.update("UPDATE team_notification_outbox SET payload_version = 2 WHERE idempotency_key = ?",
                uuidBytes(key));
        double unsupportedBefore = counterSum(NotificationOutboxRelay.METRIC_UNSUPPORTED_VERSION);

        Instant before = dbNowUtc();
        drain();

        Map<String, Object> row = outboxRow(key);
        assertThat(row.get("status")).isEqualTo("PENDING");
        assertThat(((Number) row.get("attempt_count")).intValue()).isZero();
        assertThat(instantOf(row.get("next_attempt_at"))).isAfterOrEqualTo(before.plusSeconds(50));
        assertThat(counterSum(NotificationOutboxRelay.METRIC_UNSUPPORTED_VERSION) - unsupportedBefore)
                .isGreaterThanOrEqualTo(1.0);

        for (int i = 0; i < NotificationOutboxRelay.MAX_ATTEMPTS + 2; i++) {
            makeDueNow(membershipId);
            drain();
        }
        assertThat(statusOf(membershipId)).as("何度読めなくても DEAD にしない").isEqualTo("PENDING");
        assertThat(attemptOf(membershipId)).isZero();
        verify(ingestSpy, never()).ingest(argThat(p -> p != null
                && Long.valueOf(membershipId).equals(p.fanout().sourceId())));

        // reader を入れる（版2を版1と同じ形で読めるようにする）
        doReturn(true).when(codecSpy).supports(2);
        doAnswer(invocation -> codecSpy.decode(1, invocation.getArgument(1)))
                .when(codecSpy).decode(eq(2), anyString());
        makeDueNow(membershipId);
        drain();

        assertThat(statusOf(membershipId)).isEqualTo("RELAYED");
        assertThat(jobCount(key)).isEqualTo(1);
    }

    // =====================================================================
    // OB11 掃除・バッチ一覧
    // =====================================================================

    @Test
    @DisplayName("OB11 掃除は relayed_at から7日を過ぎた RELAYED と dead_at から30日を過ぎた DEAD だけを消し、回収と掃除はバッチ一覧に出る")
    void ob11_掃除は保存期間を過ぎたRELAYEDとDEADだけを消す() {
        OrgFx org = inTx(this::newOrg);
        long relayedOld = appendNotice(org);
        long relayedRecent = appendNotice(org);
        long relayedOldCreatedRecentRelayed = appendNotice(org);
        long deadOld = appendNotice(org);
        long deadRecent = appendNotice(org);
        long deadOldCreatedRecentDead = appendNotice(org);
        long pendingOld = appendNotice(org);
        setState(relayedOld, "RELAYED", "relayed_at", 8 * 24, 9 * 24);
        setState(relayedRecent, "RELAYED", "relayed_at", 6 * 24, 9 * 24);
        setState(relayedOldCreatedRecentRelayed, "RELAYED", "relayed_at", 1, 60 * 24);
        setState(deadOld, "DEAD", "dead_at", 31 * 24, 40 * 24);
        setState(deadRecent, "DEAD", "dead_at", 29 * 24, 40 * 24);
        setState(deadOldCreatedRecentDead, "DEAD", "dead_at", 1, 60 * 24);
        jdbc.update("UPDATE team_notification_outbox SET created_at = UTC_TIMESTAMP(6) - INTERVAL 40 DAY "
                + "WHERE idempotency_key = ?", uuidBytes(idempotencyKeyOf(
                        NotificationType.TEAM_ORG_APPLICATION_RECEIVED, pendingOld)));

        sweepBatch.sweep();

        assertThat(outboxRowOf(relayedOld)).as("RELAYED は relayed_at から7日で消す").isNull();
        assertThat(outboxRowOf(relayedRecent)).isNotNull();
        assertThat(outboxRowOf(relayedOldCreatedRecentRelayed)).as("起算点は created_at ではなく relayed_at").isNotNull();
        assertThat(outboxRowOf(deadOld)).as("DEAD は dead_at から30日で消す").isNull();
        assertThat(outboxRowOf(deadRecent)).isNotNull();
        assertThat(outboxRowOf(deadOldCreatedRecentDead)).as("起算点は created_at ではなく dead_at").isNotNull();
        assertThat(outboxRowOf(pendingOld)).as("PENDING は古くても消さない").isNotNull();

        assertThat(batchEndpointRegistry.find(NotificationOutboxStuckRecoveryBatch.BATCH_NAME))
                .as("回収は GET /api/v1/system-admin/batch に出る").isPresent();
        assertThat(batchEndpointRegistry.find(NotificationOutboxSweepBatch.BATCH_NAME))
                .as("掃除は GET /api/v1/system-admin/batch に出る").isPresent();
    }

    // =====================================================================
    // OB12a / OB12b 監視
    // =====================================================================

    @Test
    @DisplayName("OB12a ポーラーの lock を持ったノードが落ちても、lockAtMostFor の経過後に別ノードが引き継いで実行し、poller_last_success が更新される")
    void ob12a_落ちたノードのlockを引き継いでポーラーが動く() throws Throwable {
        SchedulerLock lock = NotificationOutboxRelay.class.getMethod("poll").getAnnotation(SchedulerLock.class);
        assertThat(lock).as("ポーラーは ShedLock で単一ノードにする").isNotNull();
        assertThat(Duration.parse(lock.lockAtMostFor())).as("落ちたノードの lock は1分で失効する")
                .isEqualTo(Duration.ofMinutes(1));

        jdbc.execute("CREATE TABLE IF NOT EXISTS shedlock (name VARCHAR(64) NOT NULL, lock_until TIMESTAMP(3) NOT NULL, "
                + "locked_at TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3), locked_by VARCHAR(255) NOT NULL, "
                + "PRIMARY KEY (name))");
        jdbc.update("DELETE FROM shedlock WHERE name = ?", lock.name());
        try {
            // 落ちたノードが lock を持ったまま（lock_until は未来）
            jdbc.update("INSERT INTO shedlock (name, lock_until, locked_at, locked_by) VALUES "
                    + "(?, UTC_TIMESTAMP(3) + INTERVAL 30 SECOND, UTC_TIMESTAMP(3), 'dead-node')", lock.name());
            LockingTaskExecutor executor = new DefaultLockingTaskExecutor(new JdbcTemplateLockProvider(
                    JdbcTemplateLockProvider.Configuration.builder().withJdbcTemplate(jdbc).usingDbTime().build()));
            LockConfiguration configuration = new LockConfiguration(Instant.now(), lock.name(),
                    Duration.parse(lock.lockAtMostFor()), Duration.parse(lock.lockAtLeastFor()));

            boolean whileHeld = executor.executeWithLock((LockingTaskExecutor.TaskWithResult<Boolean>) () -> {
                relay.poll();
                return Boolean.TRUE;
            }, configuration).wasExecuted();
            assertThat(whileHeld).as("lock が生きている間は実行しない").isFalse();

            // lockAtMostFor が過ぎた（lock_until が過去になった）
            jdbc.update("UPDATE shedlock SET lock_until = UTC_TIMESTAMP(3) - INTERVAL 1 SECOND WHERE name = ?",
                    lock.name());
            long before = Instant.now().getEpochSecond();
            boolean afterExpiry = executor.executeWithLock((LockingTaskExecutor.TaskWithResult<Boolean>) () -> {
                relay.poll();
                return Boolean.TRUE;
            }, configuration).wasExecuted();

            assertThat(afterExpiry).as("失効後は別ノードが引き継ぐ").isTrue();
            Gauge lastSuccess = meterRegistry.find(NotificationOutboxRelay.METRIC_POLLER_LAST_SUCCESS_EPOCH).gauge();
            assertThat(lastSuccess).isNotNull();
            assertThat(lastSuccess.value()).isGreaterThanOrEqualTo(before);
        } finally {
            jdbc.update("DELETE FROM shedlock WHERE name = ?", lock.name());
        }
    }

    @Test
    @DisplayName("OB12b 最古の PENDING の経過秒ゲージ（source=team）は滞留で増え、drain で0に戻る")
    void ob12b_最古のPENDINGのゲージは滞留で増えdrainで0に戻る() {
        OrgFx org = inTx(this::newOrg);
        long membershipId = appendNotice(org);
        jdbc.update("UPDATE team_notification_outbox SET created_at = UTC_TIMESTAMP(6) - INTERVAL 120 SECOND "
                + "WHERE idempotency_key = ?", uuidBytes(idempotencyKeyOf(
                        NotificationType.TEAM_ORG_APPLICATION_RECEIVED, membershipId)));

        Gauge gauge = meterRegistry.find(NotificationOutboxRelay.METRIC_OLDEST_PENDING_AGE_SECONDS)
                .tag("source", TeamNotificationOutboxSource.SOURCE_NAME).gauge();
        assertThat(gauge).as("source ごとのゲージが起動時に登録されている").isNotNull();
        assertThat(gauge.value()).as("滞留で増える").isGreaterThanOrEqualTo(110.0);

        drain();

        assertThat(gauge.value()).as("取り込まれれば0").isEqualTo(0.0);
    }

    // =====================================================================
    // ヘルパー
    // =====================================================================

    /** 状態と起算点の時刻を書き換える（{@code hoursAgo} 時間前に印、{@code createdHoursAgo} 時間前に作成）。 */
    private void setState(long membershipId, String status, String column, int hoursAgo, int createdHoursAgo) {
        jdbc.update("UPDATE team_notification_outbox SET status = ?, " + column
                        + " = UTC_TIMESTAMP(6) - INTERVAL ? HOUR, created_at = UTC_TIMESTAMP(6) - INTERVAL ? HOUR "
                        + "WHERE idempotency_key = ?",
                status, hoursAgo, createdHoursAgo,
                uuidBytes(idempotencyKeyOf(NotificationType.TEAM_ORG_APPLICATION_RECEIVED, membershipId)));
    }
}
