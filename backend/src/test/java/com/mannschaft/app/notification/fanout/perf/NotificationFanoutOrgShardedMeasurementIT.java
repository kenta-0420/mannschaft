package com.mannschaft.app.notification.fanout.perf;

import com.mannschaft.app.notification.NotificationPriority;
import com.mannschaft.app.notification.fanout.NotificationFanoutJob;
import com.mannschaft.app.notification.fanout.NotificationFanoutJobRepository;
import com.mannschaft.app.notification.fanout.NotificationFanoutJobService;
import com.mannschaft.app.notification.fanout.NotificationFanoutJobStatus;
import com.mannschaft.app.notification.fanout.NotificationFanoutWorker;
import com.mannschaft.app.role.fanout.OrgFanoutRecipientSource;
import com.mannschaft.app.support.perf.Fanout500kSeeder;
import com.mannschaft.app.support.test.AbstractMySqlIntegrationTest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * CMP-001⑤（通知 fan-out ワーカー並列化）— 50万人規模「自動シャード化 + processReady 並列排出」実測IT
 * （検分ステップ2準備）。
 *
 * <p>{@link NotificationFanoutOrg500kMeasurementIT}（#2629・単一ワーカー {@code processOne} 直呼び）は
 * 単一ワーカー実測≈190件/秒・完走43.9分であり ≤120 秒 SLO は「ワーカー並列化（次Phase）で達成する設計判断」
 * として先送りされていた。本 IT はその次Phase——{@code enqueue} の自動シャード化
 * （{@link NotificationFanoutOrgAutoShardingIT} で green 化済）と
 * {@link NotificationFanoutWorker#processReady()} の Virtual Threads 並列排出（{@code BATCH_SIZE=32}＝
 * {@code NotificationFanoutJobService#MAX_SHARDS} と整合）を組み合わせ、
 * 500,000 人 ORG 直属メンバー母集団で ≤120 秒 SLO を実測する。</p>
 *
 * <h2>規模のパラメータ化</h2>
 * <p>{@link #MEMBER_COUNT} を縮小すれば smoke 実行できる（配線・アサート・PERF_MEASURE 出力の確認用）。
 * 既定は {@link Fanout500kSeeder#DEFAULT_MEMBER_COUNT}（50万）。50万本走はこの出陣の範囲外であり、
 * CI にも載せない（{@code @Tag("perf")} で {@code perfTest} 専用タスクのみが実行する）。</p>
 *
 * <h2>SLO アサートは詐称しない（hard assert）</h2>
 * <p>{@code wallSeconds <= 120} は本 campaign の SLO 目標であり、達成なら緑、未達ならこの IT 自体が
 * red になって実測値と共に正直に報告する。favorable な数値へすり替えるための緩和・削除は禁止。</p>
 *
 * <h2>SKIP 偽緑への注意</h2>
 * <p>基底 {@code @EnabledIf(isDockerAvailable)} は Docker 不通で<b>静かに SKIP</b> する。測定値を得るには
 * 実 RUN（"Tests run: N", skipped=0）を確認すること。</p>
 */
@DisplayName("通知 fan-out 50万人規模 自動シャード化+並列排出 実測IT（CMP-001⑤・検分ステップ2）")
@Tag("perf")
@EnabledIf("com.mannschaft.app.support.test.AbstractMySqlIntegrationTest#isDockerAvailable")
class NotificationFanoutOrgShardedMeasurementIT extends AbstractMySqlIntegrationTest {

    private static final Logger log = LoggerFactory.getLogger(NotificationFanoutOrgShardedMeasurementIT.class);

    /** 母集団規模。smoke 実行時のみ縮小し、確認後は必ず既定値へ戻すこと。 */
    private static final int MEMBER_COUNT = Fanout500kSeeder.DEFAULT_MEMBER_COUNT;

    /** ≤120 秒 SLO（本 campaign の目標値。マスター裁可 2026-08-06 の次Phaseで達成を狙う）。 */
    private static final double WALL_SECONDS_SLO = 120.0;

    /**
     * {@code processReady} の空振り（claim 0 件）が連続してもハングしないための安全弁。
     * 500,000 人・25 シャードは 1 回の {@code processReady}（BATCH_SIZE=32）で全 claim できる想定であり、
     * 通常は 1〜数回のループで完了する。
     */
    private static final int MAX_POLL_ROUNDS = 200;

    @Autowired
    private JdbcTemplate jdbc;
    @Autowired
    private NotificationFanoutJobService jobService;
    @Autowired
    private NotificationFanoutJobRepository jobRepository;
    @Autowired
    private NotificationFanoutWorker worker;

    // =====================================================================
    // メイン: enqueue 自動シャード化 + processReady 並列排出で ≤120秒 SLO を実測
    // =====================================================================
    @Test
    @DisplayName("50万 ACTIVE ORG メンバーへの fan-out: 自動シャード化+processReady並列排出で完走・欠落0・全DONE・≤120秒を実測")
    void org500kShardedFanoutMeetsSlo() {
        Fanout500kSeeder seeder = new Fanout500kSeeder(jdbc);
        Fanout500kSeeder.SeedResult seedResult = seeder.seed(MEMBER_COUNT);
        log.info("[fanout-sharded-500k-seed] 投入完了: org={} count={} ({} ms)",
                seedResult.organizationId(), seedResult.memberCount(), seedResult.seedMs());

        String type = "FANOUT_SHARDED_500K_MAIN";
        UUID sourceEvent = UUID.randomUUID();

        // --- enqueue（自動シャード化。500,000人は shard_count=25 本のジョブ行に分割される想定） ---
        long tEnqueue0 = System.nanoTime();
        jobService.enqueue(OrgFanoutRecipientSource.SCOPE_TYPE, String.valueOf(seedResult.organizationId()),
                type, sourceEvent, seedResult.organizationId(), "CMP-001 50万シャード並列実測", "本文",
                NotificationPriority.NORMAL, "FANOUT_SHARDED_500K_IT", null, "/x", null, true);
        long enqueueMs = (System.nanoTime() - tEnqueue0) / 1_000_000;

        // enqueue は真の O(1)（AC-7）: 母集団に依らず親ジョブ 1 行・shard_count=0（未評価）だけを作る。
        // シャード確定・分割は初回 claim した worker（resolveAndSplitShards）が行い、1 波では終わらず複数波になる。
        List<NotificationFanoutJob> afterEnqueue = jobRepository
                .findByScopeTypeAndScopeRefAndNotificationTypeAndSourceEventUuidOrderByShardIndexAsc(
                        OrgFanoutRecipientSource.SCOPE_TYPE, String.valueOf(seedResult.organizationId()),
                        type, sourceEvent);
        assertThat(afterEnqueue).as("AC-7: enqueue 直後は親ジョブ 1 行だけ").hasSize(1);
        assertThat(afterEnqueue.get(0).getShardCount()).as("AC-7: enqueue 直後は shard_count=0（未評価）")
                .isEqualTo((short) 0);
        log.info("[fanout-sharded-500k] enqueueMs={} enqueueRows={} shardCount(pending)=0", enqueueMs,
                afterEnqueue.size());

        // --- processReady() を全シャード DONE まで壁時計計測しつつループ排出 ---
        long tWall0 = System.nanoTime();
        int rounds = 0;
        while (!allShardsDone(seedResult.organizationId(), type, sourceEvent) && rounds < MAX_POLL_ROUNDS) {
            worker.processReady();
            rounds++;
        }
        long wallMs = (System.nanoTime() - tWall0) / 1_000_000;
        double wallSeconds = wallMs / 1000.0;

        List<NotificationFanoutJob> finalJobs = jobRepository
                .findByScopeTypeAndScopeRefAndNotificationTypeAndSourceEventUuidOrderByShardIndexAsc(
                        OrgFanoutRecipientSource.SCOPE_TYPE, String.valueOf(seedResult.organizationId()),
                        type, sourceEvent);
        int shardCount = finalJobs.size();
        long generated = countNotifications(type);
        long distinct = countDistinctUsers(type);
        double throughputPerSecond = wallSeconds > 0 ? generated / wallSeconds : generated;

        log.info("[fanout-sharded-500k] enqueueMs={} wallSeconds={} rounds={} shardCount={} generated={} distinct={} "
                        + "throughputPerSecond={} statuses={}",
                enqueueMs, wallSeconds, rounds, shardCount, generated, distinct, throughputPerSecond,
                finalJobs.stream().map(NotificationFanoutJob::getStatus).toList());
        perf("seed_ms=" + seedResult.seedMs()
                + " enqueue_ms=" + enqueueMs
                + " wall_seconds=" + wallSeconds
                + " shard_count=" + shardCount
                + " generated=" + generated
                + " distinct=" + distinct
                + " throughput_per_second=" + throughputPerSecond
                + " poll_rounds=" + rounds
                + " member_count=" + MEMBER_COUNT);

        // --- AC群（詐称禁止・hard assert） ---
        assertThat(enqueueMs).as("enqueue はジョブ行 INSERT のみで応答は300ms未満").isLessThan(300);
        assertThat(shardCount).as("500,000人はshard_count=25本のジョブ行に自動分割される").isEqualTo(25);
        assertThat(rounds).as("MAX_POLL_ROUNDS 到達＝ハング（安全弁未達）ではないことの確認")
                .isLessThan(MAX_POLL_ROUNDS);
        // 本 campaign の SLO 本体。未達なら実測値と共に red で正直に報告する（すり替え・緩和禁止）。
        assertThat(wallSeconds).as("processReady並列排出で≤120秒SLOを達成（CMP-001⑤ 検分ステップ2）")
                .isLessThanOrEqualTo(WALL_SECONDS_SLO);
        assertThat(generated).as("生成された notifications 件数は母集団と一致（欠落0）").isEqualTo(MEMBER_COUNT);
        assertThat(distinct).as("DISTINCT user_id も母集団と一致（重複配信なし・クラッシュ無しの正常測定）")
                .isEqualTo(MEMBER_COUNT);
        assertThat(finalJobs).as("全シャードジョブが最終的に DONE（DEAD_LETTER 皆無）")
                .allSatisfy(j -> assertThat(j.getStatus()).isEqualTo(NotificationFanoutJobStatus.DONE));
    }

    // =====================================================================
    // ヘルパ
    // =====================================================================

    private boolean allShardsDone(long organizationId, String type, UUID sourceEvent) {
        List<NotificationFanoutJob> jobs = jobRepository
                .findByScopeTypeAndScopeRefAndNotificationTypeAndSourceEventUuidOrderByShardIndexAsc(
                        OrgFanoutRecipientSource.SCOPE_TYPE, String.valueOf(organizationId), type, sourceEvent);
        return !jobs.isEmpty() && jobs.stream().allMatch(j -> j.getStatus() == NotificationFanoutJobStatus.DONE);
    }

    private static void perf(String kv) {
        System.out.println("PERF_MEASURE " + kv);
    }

    private long countNotifications(String type) {
        Long c = jdbc.queryForObject(
                "SELECT COUNT(*) FROM notifications WHERE notification_type = ?", Long.class, type);
        return c == null ? 0L : c;
    }

    private long countDistinctUsers(String type) {
        Long c = jdbc.queryForObject(
                "SELECT COUNT(DISTINCT user_id) FROM notifications WHERE notification_type = ?", Long.class, type);
        return c == null ? 0L : c;
    }
}
