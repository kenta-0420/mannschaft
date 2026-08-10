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
 * CMP-001⑤（通知 fan-out ワーカー並列化）— enqueue 自動シャード化の受け入れテスト（red・試練）。
 *
 * <p>マスター裁可: 「enqueue は母集団しきい値で自動シャード化する。ORG 配信で受信者数が閾値超なら
 * enqueue が内部で {@code shard_count} を算出し N 本のシャード job 行を発行する。閾値未満は
 * {@code shard_count=1}（従来通り・単一行）」を固定する。
 *
 * <p>しきい値の<b>厳密値</b>には依存しない。「明らかに閾値超」「明らかに閾値未満／ゼロ」の 2 極で検証する。
 *
 * <h2>B案（worker 側シャード分割）への追随</h2>
 * <p>enqueue は真の O(1)（AC-7）へ是正され、受信者数に依らず<b>親ジョブ 1 行だけ</b>を
 * {@code shard_count=0}（未評価の番人値）で INSERT する。シャード数 N の確定と子シャード行の発行は初回
 * {@code claim} した worker が {@link NotificationFanoutJobService#resolveAndSplitShards} で行う。よって本 IT は
 * 「enqueue 直後は 1 行・{@code shard_count=0}」を確認し、{@link NotificationFanoutWorker#processReady()} を
 * 完走までループ呼びして「N 本へ分割され欠落0・全 DONE」を検証する。</p>
 */
@DisplayName("通知 fan-out enqueue 自動シャード化の実測IT（CMP-001⑤・red）")
@Tag("perf")
@EnabledIf("com.mannschaft.app.support.test.AbstractMySqlIntegrationTest#isDockerAvailable")
class NotificationFanoutOrgAutoShardingIT extends AbstractMySqlIntegrationTest {

    private static final Logger log = LoggerFactory.getLogger(NotificationFanoutOrgAutoShardingIT.class);

    /** 明らかに閾値超と見なせる母集団（出陣-1 seeder が実測済みの規模帯を踏襲）。50000→shard_count=3。 */
    private static final int LARGE_POPULATION = 50_000;

    /** {@code processReady} 空振り連続でもハングしないための安全弁。 */
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
    // AC-1 / AC-2: 大母集団は複数シャードjob行に分割され、全シャード処理で欠落0・全DONE
    // =====================================================================
    @Test
    @DisplayName("AC-1/AC-2 大母集団はenqueue直後は1行(shard_count=0)、processReady初回で3本へ分割され欠落0・全DONE")
    void ac1ac2_largePopulationAutoShardsAndFullyDrains() {
        Fanout500kSeeder seeder = new Fanout500kSeeder(jdbc);
        Fanout500kSeeder.SeedResult seed = seeder.seed(LARGE_POPULATION);

        String type = "FANOUT_SHARD_AC12";
        UUID sourceEvent = UUID.randomUUID();
        jobService.enqueue(OrgFanoutRecipientSource.SCOPE_TYPE, String.valueOf(seed.organizationId()),
                type, sourceEvent, seed.organizationId(), "AC-1/2 自動シャード化", "本文",
                NotificationPriority.NORMAL, "FANOUT_SHARD_IT", null, "/x", null, true);

        // enqueue は O(1)（AC-7）: 母集団に依らず親ジョブ 1 行・shard_count=0（未評価）だけを作る。
        List<NotificationFanoutJob> afterEnqueue = jobRepository
                .findByScopeTypeAndScopeRefAndNotificationTypeAndSourceEventUuidOrderByShardIndexAsc(
                        OrgFanoutRecipientSource.SCOPE_TYPE, String.valueOf(seed.organizationId()), type, sourceEvent);
        perf("AC12_population=" + LARGE_POPULATION + " AC12_enqueue_rows=" + afterEnqueue.size());
        assertThat(afterEnqueue).as("AC-7: enqueue 直後は親ジョブ 1 行だけ").hasSize(1);
        assertThat(afterEnqueue.get(0).getShardCount()).as("AC-7: enqueue 直後は shard_count=0（未評価）")
                .isEqualTo((short) 0);

        // processReady を全 DONE までループ排出（初回で worker が母集団を数えて N 本へ分割）。
        int rounds = drainUntilDone(seed.organizationId(), type, sourceEvent);

        List<NotificationFanoutJob> finalJobs = jobRepository
                .findByScopeTypeAndScopeRefAndNotificationTypeAndSourceEventUuidOrderByShardIndexAsc(
                        OrgFanoutRecipientSource.SCOPE_TYPE, String.valueOf(seed.organizationId()), type, sourceEvent);

        log.info("[AC-1/2] population={} finalShards={} rounds={}", LARGE_POPULATION, finalJobs.size(), rounds);
        perf("AC12_shard_rows=" + finalJobs.size() + " AC12_poll_rounds=" + rounds);

        // AC-1: 50000 → shard_count=ceil(50000/20000)=3 本のジョブ行に分割される。
        assertThat(finalJobs).as("AC-1: 大母集団は 3 本のシャードジョブに分割される").hasSize(3);
        assertThat(finalJobs).as("AC-1: 各ジョブ行の shard_count は生成本数(3)と一致する")
                .allSatisfy(j -> assertThat(j.getShardCount()).isEqualTo((short) finalJobs.size()));
        // shard_index は 0..N-1 が過不足なく揃う。
        List<Short> indices = finalJobs.stream().map(NotificationFanoutJob::getShardIndex).sorted().toList();
        for (int i = 0; i < finalJobs.size(); i++) {
            assertThat(indices.get(i)).as("AC-1: shard_index は 0..N-1 が過不足なく揃う").isEqualTo((short) i);
        }

        // AC-2: 全シャード完走で generated 件数 == 母集団（欠落0）・全シャード DONE。
        long delivered = countNotifications(type);
        long distinct = countDistinctUsers(type);
        log.info("[AC-2] delivered={} distinct={} statuses={}", delivered, distinct,
                finalJobs.stream().map(NotificationFanoutJob::getStatus).toList());
        perf("AC2_delivered=" + delivered + " AC2_distinct=" + distinct);

        assertThat(delivered).as("AC-2: 全シャード処理後の配信件数は母集団と一致（欠落0）")
                .isEqualTo(LARGE_POPULATION);
        assertThat(distinct).as("AC-2: DISTINCT user_id はちょうど母集団（別ユーザー混入・重複配信なし）")
                .isEqualTo(LARGE_POPULATION);
        assertThat(finalJobs).as("AC-2: 全シャードが最終的に DONE")
                .allSatisfy(j -> assertThat(j.getStatus()).isEqualTo(NotificationFanoutJobStatus.DONE));
    }

    // =====================================================================
    // AC-3: 母集団0のORGは shard_count==1 で即DONE・0件
    // =====================================================================
    @Test
    @DisplayName("AC-3 母集団0のORGはshard_count==1の単一ジョブ行のまま即DONE・配信0件")
    void ac3_zeroPopulationStaysSingleShardAndCompletesImmediately() {
        Fanout500kSeeder seeder = new Fanout500kSeeder(jdbc);
        Fanout500kSeeder.SeedResult seed = seeder.seed(0);

        String type = "FANOUT_SHARD_AC3";
        UUID sourceEvent = UUID.randomUUID();
        jobService.enqueue(OrgFanoutRecipientSource.SCOPE_TYPE, String.valueOf(seed.organizationId()),
                type, sourceEvent, seed.organizationId(), "AC-3 母集団ゼロ", "本文",
                NotificationPriority.NORMAL, "FANOUT_SHARD_IT", null, "/x", null, true);

        List<NotificationFanoutJob> jobs = jobRepository
                .findByScopeTypeAndScopeRefAndNotificationTypeAndSourceEventUuidOrderByShardIndexAsc(
                        OrgFanoutRecipientSource.SCOPE_TYPE, String.valueOf(seed.organizationId()), type, sourceEvent);

        perf("AC3_enqueue_rows=" + jobs.size());
        assertThat(jobs).as("AC-3: enqueue 直後は親ジョブ 1 行").hasSize(1);
        assertThat(jobs.get(0).getShardCount()).as("AC-3: enqueue 直後は shard_count=0（未評価）").isEqualTo((short) 0);

        // 母集団0のため worker は分割で N=1 と確定し（子シャードなし）、空ページで即 DONE。
        worker.processOne(jobs.get(0));

        List<NotificationFanoutJob> finalJobs = jobRepository
                .findByScopeTypeAndScopeRefAndNotificationTypeAndSourceEventUuidOrderByShardIndexAsc(
                        OrgFanoutRecipientSource.SCOPE_TYPE, String.valueOf(seed.organizationId()), type, sourceEvent);
        long delivered = countNotifications(type);
        perf("AC3_shard_rows=" + finalJobs.size());
        assertThat(finalJobs).as("AC-3: 母集団0は単一ジョブ行のまま（子シャードを増やさない）").hasSize(1);
        assertThat(finalJobs.get(0).getShardCount()).as("AC-3: 確定後 shard_count==1").isEqualTo((short) 1);
        assertThat(finalJobs.get(0).getStatus()).as("AC-3: 即DONE").isEqualTo(NotificationFanoutJobStatus.DONE);
        assertThat(delivered).as("AC-3: 配信0件").isZero();
    }

    // =====================================================================
    // AC-8: shard_count=1 の単一経路（既存VILLAGE/TEAM/ORG小規模）は既存挙動を退行なく維持
    // =====================================================================
    @Test
    @DisplayName("AC-8 明らかに閾値未満の小母集団はshard_count==1のまま既存の単一ジョブ経路で完走する（回帰）")
    void ac8_smallPopulationStaysSingleShardRegression() {
        Fanout500kSeeder seeder = new Fanout500kSeeder(jdbc);
        Fanout500kSeeder.SeedResult seed = seeder.seed(5);

        String type = "FANOUT_SHARD_AC8";
        UUID sourceEvent = UUID.randomUUID();
        jobService.enqueue(OrgFanoutRecipientSource.SCOPE_TYPE, String.valueOf(seed.organizationId()),
                type, sourceEvent, seed.organizationId(), "AC-8 小母集団回帰", "本文",
                NotificationPriority.NORMAL, "FANOUT_SHARD_IT", null, "/x", null, true);

        List<NotificationFanoutJob> jobs = jobRepository
                .findByScopeTypeAndScopeRefAndNotificationTypeAndSourceEventUuidOrderByShardIndexAsc(
                        OrgFanoutRecipientSource.SCOPE_TYPE, String.valueOf(seed.organizationId()), type, sourceEvent);

        perf("AC8_enqueue_rows=" + jobs.size());
        assertThat(jobs).as("AC-8: enqueue 直後は親ジョブ 1 行").hasSize(1);
        assertThat(jobs.get(0).getShardCount()).as("AC-8: enqueue 直後は shard_count=0（未評価）").isEqualTo((short) 0);

        // 閾値未満のため worker は N=1 と確定し、単一経路で完走する（既存挙動）。
        worker.processOne(jobs.get(0));

        List<NotificationFanoutJob> finalJobs = jobRepository
                .findByScopeTypeAndScopeRefAndNotificationTypeAndSourceEventUuidOrderByShardIndexAsc(
                        OrgFanoutRecipientSource.SCOPE_TYPE, String.valueOf(seed.organizationId()), type, sourceEvent);
        long delivered = countNotifications(type);
        perf("AC8_shard_rows=" + finalJobs.size());
        assertThat(finalJobs).as("AC-8: 閾値未満は単一ジョブ行のまま（既存挙動）").hasSize(1);
        assertThat(finalJobs.get(0).getShardCount()).as("AC-8: 確定後 shard_count==1").isEqualTo((short) 1);
        assertThat(finalJobs.get(0).getStatus()).as("AC-8: 単一経路で完走しDONE").isEqualTo(NotificationFanoutJobStatus.DONE);
        assertThat(delivered).as("AC-8: 配信件数は母集団と一致").isEqualTo(5L);
    }

    // =====================================================================
    // AC-9: 正常完走時（クラッシュなし）は重複配信0
    // =====================================================================
    @Test
    @DisplayName("AC-9 クラッシュなしの正常完走時は全シャード合計で重複配信が0件")
    void ac9_noDuplicationOnCleanCompletion() {
        Fanout500kSeeder seeder = new Fanout500kSeeder(jdbc);
        Fanout500kSeeder.SeedResult seed = seeder.seed(LARGE_POPULATION);

        String type = "FANOUT_SHARD_AC9";
        UUID sourceEvent = UUID.randomUUID();
        jobService.enqueue(OrgFanoutRecipientSource.SCOPE_TYPE, String.valueOf(seed.organizationId()),
                type, sourceEvent, seed.organizationId(), "AC-9 重複0", "本文",
                NotificationPriority.NORMAL, "FANOUT_SHARD_IT", null, "/x", null, true);

        // enqueue 直後は親 1 行（shard_count=0）。processReady 完走ループで分割→全シャード配信。
        List<NotificationFanoutJob> afterEnqueue = jobRepository
                .findByScopeTypeAndScopeRefAndNotificationTypeAndSourceEventUuidOrderByShardIndexAsc(
                        OrgFanoutRecipientSource.SCOPE_TYPE, String.valueOf(seed.organizationId()), type, sourceEvent);
        assertThat(afterEnqueue).as("AC-9 前提: enqueue 直後は親 1 行").hasSize(1);

        drainUntilDone(seed.organizationId(), type, sourceEvent);

        List<NotificationFanoutJob> jobs = jobRepository
                .findByScopeTypeAndScopeRefAndNotificationTypeAndSourceEventUuidOrderByShardIndexAsc(
                        OrgFanoutRecipientSource.SCOPE_TYPE, String.valueOf(seed.organizationId()), type, sourceEvent);
        assertThat(jobs).as("AC-9 前提: 大母集団は複数シャードに分割される").hasSizeGreaterThan(1);

        long total = countNotifications(type);
        long distinct = countDistinctUsers(type);
        perf("AC9_total=" + total + " AC9_distinct=" + distinct);

        assertThat(total).as("AC-9: 正常完走時は総配信件数==DISTINCT件数（重複0）").isEqualTo(distinct);
        assertThat(total).as("AC-9: 総配信件数は母集団と一致").isEqualTo(LARGE_POPULATION);
    }

    // =====================================================================
    // ヘルパ
    // =====================================================================

    private static void perf(String kv) {
        System.out.println("PERF_MEASURE " + kv);
    }

    /** {@code processReady()} を全シャード DONE まで（安全弁つき）ループ排出し、周回数を返す。 */
    private int drainUntilDone(long organizationId, String type, UUID sourceEvent) {
        int rounds = 0;
        while (!allShardsDone(organizationId, type, sourceEvent) && rounds < MAX_POLL_ROUNDS) {
            worker.processReady();
            rounds++;
        }
        assertThat(rounds).as("processReady が MAX_POLL_ROUNDS 未満で完走（ハングしていない）")
                .isLessThan(MAX_POLL_ROUNDS);
        return rounds;
    }

    private boolean allShardsDone(long organizationId, String type, UUID sourceEvent) {
        List<NotificationFanoutJob> jobs = jobRepository
                .findByScopeTypeAndScopeRefAndNotificationTypeAndSourceEventUuidOrderByShardIndexAsc(
                        OrgFanoutRecipientSource.SCOPE_TYPE, String.valueOf(organizationId), type, sourceEvent);
        return !jobs.isEmpty() && jobs.stream().allMatch(j -> j.getStatus() == NotificationFanoutJobStatus.DONE);
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
