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
 * <h2>なぜ今 red になるか</h2>
 * <p>{@code NotificationFanoutJobService#enqueue} は現時点では常に 1 行だけ INSERT し
 * {@code shard_count=1} を固定する（自動分割ロジック未実装・出陣で実装予定）。したがって AC-1/AC-2 は
 * 「shard 行が複数生成される」の期待に反し必ず失敗する。</p>
 */
@DisplayName("通知 fan-out enqueue 自動シャード化の実測IT（CMP-001⑤・red）")
@Tag("perf")
@EnabledIf("com.mannschaft.app.support.test.AbstractMySqlIntegrationTest#isDockerAvailable")
class NotificationFanoutOrgAutoShardingIT extends AbstractMySqlIntegrationTest {

    private static final Logger log = LoggerFactory.getLogger(NotificationFanoutOrgAutoShardingIT.class);

    /** 明らかに閾値超と見なせる母集団（出陣-1 seeder が実測済みの規模帯を踏襲）。 */
    private static final int LARGE_POPULATION = 50_000;

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
    @DisplayName("AC-1/AC-2 大母集団のenqueueはshard_count>1のジョブ行N本を生成し、全シャード処理で欠落0・全DONE")
    void ac1ac2_largePopulationAutoShardsAndFullyDrains() {
        Fanout500kSeeder seeder = new Fanout500kSeeder(jdbc);
        Fanout500kSeeder.SeedResult seed = seeder.seed(LARGE_POPULATION);

        String type = "FANOUT_SHARD_AC12";
        UUID sourceEvent = UUID.randomUUID();
        jobService.enqueue(OrgFanoutRecipientSource.SCOPE_TYPE, String.valueOf(seed.organizationId()),
                type, sourceEvent, seed.organizationId(), "AC-1/2 自動シャード化", "本文",
                NotificationPriority.NORMAL, "FANOUT_SHARD_IT", null, "/x", null, true);

        List<NotificationFanoutJob> jobs = jobRepository
                .findByScopeTypeAndScopeRefAndNotificationTypeAndSourceEventUuidOrderByShardIndexAsc(
                        OrgFanoutRecipientSource.SCOPE_TYPE, String.valueOf(seed.organizationId()), type, sourceEvent);

        log.info("[AC-1/2] population={} generatedShards={}", LARGE_POPULATION, jobs.size());
        perf("AC12_population=" + LARGE_POPULATION + " AC12_shard_rows=" + jobs.size());

        // AC-1: 明らかに閾値超の母集団は shard_count > 1 本のジョブ行に分割される。
        assertThat(jobs).as("AC-1: 大母集団は複数シャードのジョブ行に分割される").hasSizeGreaterThan(1);
        // 全シャード行の shard_count は生成本数と自己整合する。
        assertThat(jobs).as("AC-1: 各ジョブ行の shard_count は生成本数と一致する")
                .allSatisfy(j -> assertThat(j.getShardCount()).isEqualTo((short) jobs.size()));
        // shard_index は 0..N-1 が過不足なく揃う。
        List<Short> indices = jobs.stream().map(NotificationFanoutJob::getShardIndex).sorted().toList();
        for (int i = 0; i < jobs.size(); i++) {
            assertThat(indices.get(i)).as("AC-1: shard_index は 0..N-1 が過不足なく揃う").isEqualTo((short) i);
        }

        // AC-2: 全シャードをワーカーで処理すると generated 件数 == 母集団（欠落0）・全シャード DONE。
        for (NotificationFanoutJob job : jobs) {
            worker.processOne(job);
        }
        long delivered = countNotifications(type);
        long distinct = countDistinctUsers(type);
        List<NotificationFanoutJob> finalJobs = jobRepository
                .findByScopeTypeAndScopeRefAndNotificationTypeAndSourceEventUuidOrderByShardIndexAsc(
                        OrgFanoutRecipientSource.SCOPE_TYPE, String.valueOf(seed.organizationId()), type, sourceEvent);

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

        perf("AC3_shard_rows=" + jobs.size());
        assertThat(jobs).as("AC-3: 母集団0は単一ジョブ行のまま").hasSize(1);
        assertThat(jobs.get(0).getShardCount()).as("AC-3: shard_count==1").isEqualTo((short) 1);

        worker.processOne(jobs.get(0));

        NotificationFanoutJob after = jobRepository.findById(jobs.get(0).getId()).orElseThrow();
        long delivered = countNotifications(type);
        assertThat(after.getStatus()).as("AC-3: 即DONE").isEqualTo(NotificationFanoutJobStatus.DONE);
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

        perf("AC8_shard_rows=" + jobs.size());
        assertThat(jobs).as("AC-8: 閾値未満は単一ジョブ行のまま（既存挙動）").hasSize(1);
        assertThat(jobs.get(0).getShardCount()).as("AC-8: shard_count==1").isEqualTo((short) 1);

        worker.processOne(jobs.get(0));

        NotificationFanoutJob after = jobRepository.findById(jobs.get(0).getId()).orElseThrow();
        long delivered = countNotifications(type);
        assertThat(after.getStatus()).as("AC-8: 単一経路で完走しDONE").isEqualTo(NotificationFanoutJobStatus.DONE);
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

        List<NotificationFanoutJob> jobs = jobRepository
                .findByScopeTypeAndScopeRefAndNotificationTypeAndSourceEventUuidOrderByShardIndexAsc(
                        OrgFanoutRecipientSource.SCOPE_TYPE, String.valueOf(seed.organizationId()), type, sourceEvent);
        assertThat(jobs).as("AC-9 前提: 大母集団は複数シャードに分割される").hasSizeGreaterThan(1);

        for (NotificationFanoutJob job : jobs) {
            worker.processOne(job);
        }

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
