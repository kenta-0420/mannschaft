package com.mannschaft.app.notification.fanout.perf;

import com.mannschaft.app.notification.NotificationPriority;
import com.mannschaft.app.notification.NotificationScopeType;
import com.mannschaft.app.notification.fanout.NotificationFanoutJob;
import com.mannschaft.app.notification.fanout.NotificationFanoutJobRepository;
import com.mannschaft.app.notification.fanout.NotificationFanoutJobService;
import com.mannschaft.app.notification.fanout.NotificationFanoutJobStatus;
import com.mannschaft.app.notification.fanout.NotificationFanoutWorker;
import com.mannschaft.app.notification.service.NotificationBulkFanoutService;
import com.mannschaft.app.notification.service.NotificationDispatchService;
import com.mannschaft.app.role.fanout.OrgFanoutRecipientSource;
import com.mannschaft.app.support.perf.Fanout500kSeeder;
import com.mannschaft.app.support.test.AbstractMySqlIntegrationTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * CMP-001⑤（通知 fan-out ワーカー並列化）— シャード版クラッシュ再開の受け入れテスト（難所・AC-6・red）。
 *
 * <p>{@link NotificationFanoutOrgCrashResumeIT} の単一ジョブ版クラッシュ再開を N 本シャードへ拡張する。
 * 「N本シャードのうち1シャード処理中に例外注入でクラッシュ→他シャードは継続→再処理で全シャードDONE」
 * （マスター裁可 AC-6）を、{@link CrashAtChunkBulkFanoutService} で
 * <b>グローバル通し番号のチャンク呼び出し回数</b>が指定値に達した瞬間にだけ例外を投げることで検証する
 * （どのシャードがそのチャンクを処理しているかはテスト側で予測しない・実測で特定する）。</p>
 *
 * <h2>破断点の位置（#2629 CrashResume ITと同型）</h2>
 * <p>スパイは本物の {@link NotificationBulkFanoutService#insertAndDispatchChunk} を先に実行させてから
 * （＝当該チャンクの notifications 行は既にコミット済み）例外を投げる。{@link NotificationFanoutWorker#processOne}
 * は例外を内部で {@code recordFailure}（FAILED 遷移・独立コミット）に落とすため、本テストのシャードループは
 * 中断せず次シャードへ進む（「他シャードは継続」の実測）。</p>
 *
 * <h2>B案（worker 側シャード分割）への追随</h2>
 * <p>enqueue は O(1) のため親ジョブ 1 行（{@code shard_count=0}）しか作らない。本 IT はクラッシュ注入の前段で
 * {@link NotificationFanoutJobService#resolveAndSplitShards} を明示呼びしてシャードを確定・分割してから
 * （配信は伴わない）、全シャードのクラッシュ再開ロジックの正しさを検証する。</p>
 */
@DisplayName("通知 fan-out シャード版クラッシュ再開の実測IT（CMP-001⑤・難所・red）")
@Tag("perf")
@Import(NotificationFanoutOrgShardCrashResumeIT.CrashSpyConfig.class)
@EnabledIf("com.mannschaft.app.support.test.AbstractMySqlIntegrationTest#isDockerAvailable")
class NotificationFanoutOrgShardCrashResumeIT extends AbstractMySqlIntegrationTest {

    private static final Logger log = LoggerFactory.getLogger(NotificationFanoutOrgShardCrashResumeIT.class);

    /** 母集団（明らかに閾値超・複数シャードに分割される想定）。 */
    private static final int MEMBER_COUNT = 50_000;
    /** チャンクサイズ（NotificationFanoutWorker.CHUNK_SIZE と同値。private のためテスト側で複製）。 */
    private static final int CHUNK_SIZE = 500;
    /** グローバル通し番号で何チャンク目に「INSERT確定直後・advanceCursor到達前」でクラッシュさせるか。 */
    private static final int CRASH_AT_CHUNK = 40;

    @Autowired
    private JdbcTemplate jdbc;
    @Autowired
    private NotificationFanoutJobService jobService;
    @Autowired
    private NotificationFanoutJobRepository jobRepository;
    @Autowired
    private NotificationFanoutWorker worker;
    @Autowired
    private CrashAtChunkBulkFanoutService crashSpy;

    @BeforeEach
    void resetSpy() {
        crashSpy.reset(CRASH_AT_CHUNK);
    }

    @Test
    @DisplayName("AC-6 N本シャードの1本がチャンク確定直後にクラッシュ→他シャードは継続→再処理で全シャードDONE・欠落なし")
    void oneShardCrashesMidwayThenResumeCompletesAllShards() {
        Fanout500kSeeder seeder = new Fanout500kSeeder(jdbc);
        Fanout500kSeeder.SeedResult seed = seeder.seed(MEMBER_COUNT);

        String type = "FANOUT_SHARD_CRASH_AC6";
        UUID sourceEvent = UUID.randomUUID();
        jobService.enqueue(OrgFanoutRecipientSource.SCOPE_TYPE, String.valueOf(seed.organizationId()),
                type, sourceEvent, seed.organizationId(), "AC-6 シャードクラッシュ再開", "本文",
                NotificationPriority.NORMAL, "FANOUT_SHARD_IT", null, "/x", null, true);

        // enqueue は O(1)（親 1 行・shard_count=0）。クラッシュ注入の前段でシャードを確定・分割する
        // （resolveAndSplitShards は配信を伴わないため、以降のチャンク通し番号は 0 から始まる）。
        List<NotificationFanoutJob> afterEnqueue = jobRepository
                .findByScopeTypeAndScopeRefAndNotificationTypeAndSourceEventUuidOrderByShardIndexAsc(
                        OrgFanoutRecipientSource.SCOPE_TYPE, String.valueOf(seed.organizationId()), type, sourceEvent);
        assertThat(afterEnqueue).as("AC-6前提: enqueue 直後は親 1 行").hasSize(1);
        assertThat(afterEnqueue.get(0).getShardCount()).as("AC-6前提: enqueue 直後は shard_count=0").isEqualTo((short) 0);

        int splitN = jobService.resolveAndSplitShards(afterEnqueue.get(0).getId());
        assertThat(splitN).as("AC-6前提: 大母集団は複数シャードに分割される").isGreaterThan(1);

        List<NotificationFanoutJob> jobs = jobRepository
                .findByScopeTypeAndScopeRefAndNotificationTypeAndSourceEventUuidOrderByShardIndexAsc(
                        OrgFanoutRecipientSource.SCOPE_TYPE, String.valueOf(seed.organizationId()), type, sourceEvent);
        assertThat(jobs).as("AC-6前提: 分割後は複数シャードのジョブ行").hasSize(splitN);
        int shardCount = jobs.size();

        // --- 1 回目: 全シャードを順に処理する（うち1本がグローバル通し番号CRASH_AT_CHUNK目でクラッシュする）。
        for (NotificationFanoutJob job : jobs) {
            worker.processOne(job);
        }

        long insertedBeforeResume = countNotifications(type);
        List<NotificationFanoutJob> afterFirstPass = jobRepository
                .findByScopeTypeAndScopeRefAndNotificationTypeAndSourceEventUuidOrderByShardIndexAsc(
                        OrgFanoutRecipientSource.SCOPE_TYPE, String.valueOf(seed.organizationId()), type, sourceEvent);
        List<NotificationFanoutJob> notDone = afterFirstPass.stream()
                .filter(j -> j.getStatus() != NotificationFanoutJobStatus.DONE).toList();

        log.info("[crash] shardCount={} insertedBeforeResume={}（母集団未満のはず） notDoneShards={}",
                shardCount, insertedBeforeResume, notDone.size());
        perf("AC6_shard_count=" + shardCount + " AC6_inserted_before_resume=" + insertedBeforeResume
                + " AC6_not_done_shards=" + notDone.size());

        // 自己検証(1): 他シャードは継続＝クラッシュしたのはちょうど1本（残りは1回目でDONE）。
        assertThat(notDone).as("(1) クラッシュしたのはちょうど1シャード（他シャードは継続してDONE済み）")
                .hasSize(1);
        assertThat(notDone.get(0).getStatus()).as("(1) クラッシュしたシャードは FAILED（recordFailure・握り潰しではない）")
                .isEqualTo(NotificationFanoutJobStatus.FAILED);
        // 自己検証(2): クラッシュ直後は母集団未満（＝本当に中断している）。
        assertThat(insertedBeforeResume).as("(2) クラッシュ直後は母集団未満（中断が実際に起きている）")
                .isLessThan(MEMBER_COUNT);

        // --- 再開: クラッシュした1シャードだけを再処理する（他シャードは既にDONEのため再処理不要）。
        NotificationFanoutJob crashedJob = notDone.get(0);
        worker.processOne(crashedJob);

        long totalAfterResume = countNotifications(type);
        long distinctAfterResume = countDistinctUsers(type);
        List<NotificationFanoutJob> finalJobs = jobRepository
                .findByScopeTypeAndScopeRefAndNotificationTypeAndSourceEventUuidOrderByShardIndexAsc(
                        OrgFanoutRecipientSource.SCOPE_TYPE, String.valueOf(seed.organizationId()), type, sourceEvent);

        log.info("[resume] total={} distinct={} statuses={}", totalAfterResume, distinctAfterResume,
                finalJobs.stream().map(NotificationFanoutJob::getStatus).toList());
        perf("AC6_total_after_resume=" + totalAfterResume + " AC6_distinct_after_resume=" + distinctAfterResume
                + " AC6_member_count=" + MEMBER_COUNT + " AC6_shard_count=" + shardCount);

        // (3) 再開後 notifications 総数は「母集団以上・母集団+(shard_count×CHUNK_SIZE)以内」。
        assertThat(totalAfterResume).as("(3a) 再開後の notifications 総数は母集団以上（欠落なし）")
                .isGreaterThanOrEqualTo(MEMBER_COUNT);
        assertThat(totalAfterResume).as("(3b) 重複は shard_count×CHUNK_SIZE 以内に収まる（無限重複しない）")
                .isLessThanOrEqualTo(MEMBER_COUNT + (long) shardCount * CHUNK_SIZE);
        // (4) DISTINCT user_id はちょうど母集団（欠落・別ユーザー混入なし）。
        assertThat(distinctAfterResume).as("(4) DISTINCT user_id はちょうど母集団").isEqualTo(MEMBER_COUNT);
        // (5) 全シャードが最終的に DONE。
        assertThat(finalJobs).as("(5) 再開後は全シャードが最終的に DONE")
                .allSatisfy(j -> assertThat(j.getStatus()).isEqualTo(NotificationFanoutJobStatus.DONE));
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

    /**
     * 本物の {@link NotificationBulkFanoutService#insertAndDispatchChunk} を先に実行させてから
     * （＝当該チャンクの INSERT を確定コミットさせてから）グローバル通し番号で指定回数目の呼び出しでだけ
     * 例外を投げるスパイ（{@link NotificationFanoutOrgCrashResumeIT.CrashAtChunkBulkFanoutService} と同型）。
     * どのシャードジョブがその呼び出しを行っているかは関知しない＝実測でどのシャードが割を食うか特定する設計。
     */
    static class CrashAtChunkBulkFanoutService extends NotificationBulkFanoutService {
        private final AtomicInteger callCount = new AtomicInteger(0);
        private volatile int crashAtChunk = -1;

        CrashAtChunkBulkFanoutService(JdbcTemplate jdbcTemplate, NotificationDispatchService dispatchService,
                                      PlatformTransactionManager transactionManager) {
            super(jdbcTemplate, dispatchService, transactionManager);
        }

        void reset(int crashAtChunk) {
            this.callCount.set(0);
            this.crashAtChunk = crashAtChunk;
        }

        @Override
        public void insertAndDispatchChunk(List<Long> recipients, String notificationType,
                                           NotificationPriority priority, String title, String body,
                                           String sourceType, Long sourceId, NotificationScopeType scopeType,
                                           Long scopeId, String actionUrl, Long actorId, Long organizationId) {
            super.insertAndDispatchChunk(recipients, notificationType, priority, title, body,
                    sourceType, sourceId, scopeType, scopeId, actionUrl, actorId, organizationId);
            int n = callCount.incrementAndGet();
            if (n == crashAtChunk) {
                throw new RuntimeException("SHARD_CRASH_RESUME_IT: グローバル通し番号チャンク" + n + "確定直後の模擬クラッシュ");
            }
        }
    }

    @TestConfiguration
    static class CrashSpyConfig {
        @Bean
        @Primary
        CrashAtChunkBulkFanoutService crashAtChunkBulkFanoutService(
                JdbcTemplate jdbcTemplate, NotificationDispatchService dispatchService,
                PlatformTransactionManager transactionManager) {
            return new CrashAtChunkBulkFanoutService(jdbcTemplate, dispatchService, transactionManager);
        }
    }
}
