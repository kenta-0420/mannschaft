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
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * CMP-001 通知 fan-out「50万人規模」負荷試験ハーネス — ORGANIZATION スコープ実測 IT。
 *
 * <p>{@link Fanout500kSeeder} で 1 組織直属の50万 ACTIVE メンバーを JDBC バッチ投入し、
 * {@code NotificationFanoutJobService#enqueue} → {@link NotificationFanoutWorker#processOne} を
 * 直接呼んで（test プロファイルは {@code @EnableScheduling} 無効のため）完走させ、
 * enqueue 応答・完走壁時計・生成件数・最終ステータスを実測する。</p>
 *
 * <h2>規模のパラメータ化</h2>
 * <p>{@link #MEMBER_COUNT} を縮小すれば smoke 実行できる（配線・アサート・PERF_MEASURE 出力の確認用）。
 * 既定は {@link Fanout500kSeeder#DEFAULT_MEMBER_COUNT}（50万）。50万本走（約40分）はこの出陣の範囲外で
 * あり、CI にも載せない（{@code @Tag("perf")} で {@code perfTest} 専用タスクのみが実行する）。</p>
 *
 * <h2>SKIP 偽緑への注意</h2>
 * <p>基底 {@code @EnabledIf(isDockerAvailable)} は Docker 不通で<b>静かに SKIP</b> する。測定値を得るには
 * 実 RUN（"Tests run: N", skipped=0）を確認すること。</p>
 */
@DisplayName("通知 fan-out 50万人規模 ORGANIZATION 実測IT（CMP-001）")
@Tag("perf")
@EnabledIf("com.mannschaft.app.support.test.AbstractMySqlIntegrationTest#isDockerAvailable")
class NotificationFanoutOrg500kMeasurementIT extends AbstractMySqlIntegrationTest {

    private static final Logger log = LoggerFactory.getLogger(NotificationFanoutOrg500kMeasurementIT.class);

    /** 母集団規模。smoke 実行時のみ縮小し、確認後は必ず既定値へ戻すこと。 */
    private static final int MEMBER_COUNT = Fanout500kSeeder.DEFAULT_MEMBER_COUNT;

    @Autowired
    private JdbcTemplate jdbc;
    @Autowired
    private NotificationFanoutJobService jobService;
    @Autowired
    private NotificationFanoutJobRepository jobRepository;
    @Autowired
    private NotificationFanoutWorker worker;

    private static volatile boolean seeded = false;
    private static Fanout500kSeeder.SeedResult seedResult;

    @BeforeEach
    void setUp() {
        if (seeded) {
            return;
        }
        synchronized (NotificationFanoutOrg500kMeasurementIT.class) {
            if (seeded) {
                return;
            }
            Fanout500kSeeder seeder = new Fanout500kSeeder(jdbc);
            seedResult = seeder.seed(MEMBER_COUNT);
            log.info("[fanout500k-seed] 投入完了: org={} userIdFrom={} userIdTo={} count={} ({} ms)",
                    seedResult.organizationId(), seedResult.userIdFrom(), seedResult.userIdTo(),
                    seedResult.memberCount(), seedResult.seedMs());
            perf("SEED_ms=" + seedResult.seedMs() + " memberCount=" + seedResult.memberCount());
            seeded = true;
        }
    }

    // =====================================================================
    // メイン: enqueue 応答・完走壁時計・生成件数・最終ステータス（SLO）
    // =====================================================================
    @Test
    @DisplayName("50万 ACTIVE ORG メンバーへの fan-out: enqueue<300ms・完走<=120s・生成=母集団・非DEAD_LETTER")
    void org500kFanoutMeetsSlo() {
        String type = "FANOUT_500K_MAIN";
        UUID sourceEvent = UUID.randomUUID();

        long t0 = System.nanoTime();
        jobService.enqueue(OrgFanoutRecipientSource.SCOPE_TYPE, String.valueOf(seedResult.organizationId()),
                type, sourceEvent, seedResult.organizationId(), "CMP-001 50万実測", "本文",
                NotificationPriority.NORMAL, "FANOUT_500K_IT", null, "/x", null, true);
        long enqueueMs = (System.nanoTime() - t0) / 1_000_000;

        NotificationFanoutJob job = jobRepository
                .findByScopeTypeAndScopeRefAndNotificationTypeAndSourceEventUuid(
                        OrgFanoutRecipientSource.SCOPE_TYPE, String.valueOf(seedResult.organizationId()),
                        type, sourceEvent)
                .orElseThrow();

        long t1 = System.nanoTime();
        worker.processOne(job);
        long wallMs = (System.nanoTime() - t1) / 1_000_000;
        double wallSeconds = wallMs / 1000.0;

        NotificationFanoutJob reloaded = jobRepository.findById(job.getId()).orElseThrow();
        long generatedNotifications = countNotifications(type);

        log.info("[500k] enqueueMs={} wallSeconds={} generated={} insertedCount={} status={}",
                enqueueMs, wallSeconds, generatedNotifications, reloaded.getInsertedCount(), reloaded.getStatus());
        perf("enqueue_ms=" + enqueueMs + " wall_seconds=" + wallSeconds
                + " generated=" + generatedNotifications + " inserted_count=" + reloaded.getInsertedCount()
                + " status=" + reloaded.getStatus() + " member_count=" + MEMBER_COUNT);

        assertThat(enqueueMs).as("enqueue はジョブ1行 INSERT のみで応答は300ms未満").isLessThan(300);
        assertThat(wallSeconds).as("完走壁時計は120秒以内").isLessThanOrEqualTo(120.0);
        assertThat(generatedNotifications).as("生成された notifications 件数は母集団と一致").isEqualTo(MEMBER_COUNT);
        assertThat(reloaded.getInsertedCount()).as("job.insertedCount も母集団と一致").isEqualTo(MEMBER_COUNT);
        assertThat(reloaded.getStatus()).as("DEAD_LETTER に陥っていない").isNotEqualTo(NotificationFanoutJobStatus.DEAD_LETTER);
    }

    // =====================================================================
    // AC-6: 母集団0件のジョブは受信者ページング0回で即 DONE（例外なし）
    // =====================================================================
    @Test
    @DisplayName("AC-6 母集団0件の ORG ジョブは即 DONE（0件・例外なし）")
    void ac6_emptyPopulationCompletesImmediatelyAsDone() {
        // 実在しない組織 ID（メンバー0件）を scope_ref に使う。
        long emptyOrgId = seedResult.organizationId() + 999_999_999L;
        String type = "FANOUT_500K_AC6";
        UUID sourceEvent = UUID.randomUUID();

        jobService.enqueue(OrgFanoutRecipientSource.SCOPE_TYPE, String.valueOf(emptyOrgId), type, sourceEvent,
                emptyOrgId, "AC-6 空母集団", "本文", NotificationPriority.NORMAL, "FANOUT_500K_IT", null, "/x", null);
        NotificationFanoutJob job = jobRepository
                .findByScopeTypeAndScopeRefAndNotificationTypeAndSourceEventUuid(
                        OrgFanoutRecipientSource.SCOPE_TYPE, String.valueOf(emptyOrgId), type, sourceEvent)
                .orElseThrow();

        worker.processOne(job);

        NotificationFanoutJob reloaded = jobRepository.findById(job.getId()).orElseThrow();
        long generated = countNotifications(type);
        log.info("[AC-6] status={} generated={}", reloaded.getStatus(), generated);
        perf("AC6_status=" + reloaded.getStatus() + " AC6_generated=" + generated);

        assertThat(reloaded.getStatus()).as("AC-6: 空母集団は例外なく即 DONE").isEqualTo(NotificationFanoutJobStatus.DONE);
        assertThat(generated).as("AC-6: 通知は1件も生成しない").isZero();
    }

    // =====================================================================
    // AC-7: CHUNK(500)/1000 境界のページングが終端まで正しく終わる（重複・欠落なし）
    // =====================================================================
    @Test
    @DisplayName("AC-7 CHUNK境界(500刻み)のページングは終端で正しく空返しし重複なく完走する")
    void ac7_chunkBoundaryPagingTerminatesCleanly() {
        // NotificationFanoutWorker.CHUNK_SIZE = 500。境界をまたぐ 1000+1 件の小規模母集団を別組織で作る。
        int boundaryCount = 1001;
        Fanout500kSeeder boundarySeeder = new Fanout500kSeeder(jdbc);
        Fanout500kSeeder.SeedResult boundaryResult = boundarySeeder.seed(boundaryCount);

        String type = "FANOUT_500K_AC7";
        UUID sourceEvent = UUID.randomUUID();
        jobService.enqueue(OrgFanoutRecipientSource.SCOPE_TYPE, String.valueOf(boundaryResult.organizationId()),
                type, sourceEvent, boundaryResult.organizationId(), "AC-7 CHUNK境界", "本文",
                NotificationPriority.NORMAL, "FANOUT_500K_IT", null, "/x", null);
        NotificationFanoutJob job = jobRepository
                .findByScopeTypeAndScopeRefAndNotificationTypeAndSourceEventUuid(
                        OrgFanoutRecipientSource.SCOPE_TYPE, String.valueOf(boundaryResult.organizationId()),
                        type, sourceEvent)
                .orElseThrow();

        worker.processOne(job);

        NotificationFanoutJob reloaded = jobRepository.findById(job.getId()).orElseThrow();
        long generated = countNotifications(type);
        long distinct = countDistinctUsers(type);
        log.info("[AC-7] boundaryCount={} generated={} distinct={} status={}",
                boundaryCount, generated, distinct, reloaded.getStatus());
        perf("AC7_boundary_count=" + boundaryCount + " AC7_generated=" + generated
                + " AC7_distinct=" + distinct + " AC7_status=" + reloaded.getStatus());

        assertThat(generated).as("AC-7: CHUNK境界をまたいでも欠落なく全件生成").isEqualTo(boundaryCount);
        assertThat(distinct).as("AC-7: 重複配信なし（DISTINCT user_id も母集団と一致）").isEqualTo(boundaryCount);
        assertThat(reloaded.getStatus()).as("AC-7: 終端の空ページで正しく DONE").isEqualTo(NotificationFanoutJobStatus.DONE);
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
