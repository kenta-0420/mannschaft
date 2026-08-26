package com.mannschaft.app.notification.fanout.perf;

import com.mannschaft.app.notification.fanout.FanoutMessageKind;
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
 * CMP-001「50万人負荷試験ハーネス」— クラッシュ再開（at-least-once）の実測 IT（難所・三番隊）。
 *
 * <p>母集団は 20,000 件（{@link NotificationFanoutWorker#CHUNK_SIZE}=500 で 40 チャンク）に縮小する。
 * 50万件そのもので都度クラッシュを再現するのは低速で本質的でない。「1 チャンクぶんの INSERT が確定した
 * <b>直後</b>・カーソル前進（{@code advanceCursor}）が起きる<b>前</b>にプロセスが落ちる」という P2 の
 * クラッシュ再開契約（AC-2・{@link NotificationFanoutWorker} javadoc 参照）を、実際に「コミット済み
 * INSERT・カーソル未前進」の状態を作ってから再開させることで検証する。</p>
 *
 * <h2>破断点の位置（なぜ本当にクラッシュ再開を検証しているか）</h2>
 * <p>{@link CrashAtChunkBulkFanoutService} は本物の {@link NotificationBulkFanoutService#insertAndDispatchChunk}
 * を<b>まず実行させてから</b>（＝そのチャンクの notifications 行は {@code REQUIRES_NEW} で既にコミット済み）
 * 例外を投げる。{@link NotificationFanoutWorker#processOne} の呼び出し順は
 * 「{@code insertAndDispatchChunk}（チャンク確定）→ {@code job.setCursorSubjectId}（in-memory 前進）→
 * {@code jobService.advanceCursor}（DB 独立コミット）」であり、本スパイは<b>1 番目の直後・2 番目に届く前</b>で
 * 例外を投げる。したがって：</p>
 * <ul>
 *   <li>クラッシュ時点で「その回のチャンク分の notifications は DB に確定済み」（挿入は取り消されない・
 *       {@code chunkTxTemplate} は REQUIRES_NEW で本例外とは無関係に既にコミット済み）</li>
 *   <li>一方で {@code job.cursor_subject_id} は<b>そのチャンクの直前の値のまま</b>（advanceCursor 未到達）</li>
 * </ul>
 * <p>この状態で {@link NotificationFanoutWorker#processOne} を（本物の {@code NotificationBulkFanoutService} で）
 * 再度呼ぶと、ワーカーは cursor が指す「クラッシュしたチャンクの受信者」を<b>再度</b>ページ取得し、
 * 同じ受信者へもう一度 INSERT する（at-least-once・重複は当該チャンク分の最大 500 件に収まる）。
 * 本テストはこの重複件数が {@code CHUNK_SIZE}（500）を超えないことを固定し、「本当に crash 直後から
 * 再開しているか」を自己検証する（cursor が正しく前のチャンク境界に留まっていなければ、重複ゼロ／
 * 母集団未満の欠落など別の壊れ方をするため、境界を跨いだ検証になる）。</p>
 *
 * <h2>SKIP 偽緑への注意</h2>
 * <p>基底 {@code @EnabledIf(isDockerAvailable)} は Docker 不通で<b>静かに SKIP</b> する。測定値を得るには
 * 実 RUN（"Tests run: N", skipped=0）を確認すること。</p>
 */
@DisplayName("通知 fan-out クラッシュ再開の実測IT（CMP-001・20,000件=40チャンク）")
@Tag("perf")
@Import(NotificationFanoutOrgCrashResumeIT.CrashSpyConfig.class)
@EnabledIf("com.mannschaft.app.support.test.AbstractMySqlIntegrationTest#isDockerAvailable")
class NotificationFanoutOrgCrashResumeIT extends AbstractMySqlIntegrationTest {

    private static final Logger log = LoggerFactory.getLogger(NotificationFanoutOrgCrashResumeIT.class);

    /** 母集団（40チャンクぴったり）。 */
    private static final int MEMBER_COUNT = 20_000;
    /** チャンクサイズ（NotificationFanoutWorker.CHUNK_SIZE と同値。private のためテスト側で複製）。 */
    private static final int CHUNK_SIZE = 500;
    /** 何チャンク目で「INSERT 確定直後・advanceCursor 到達前」にクラッシュさせるか（半ば付近）。 */
    private static final int CRASH_AT_CHUNK = 20;

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
    @DisplayName("チャンクN確定直後（cursor未前進）でクラッシュ→再開で欠落なし・重複は最大1チャンク・最終DONE")
    void crashAfterChunkCommitThenResumeIsAtLeastOnceBoundedDuplication() {
        Fanout500kSeeder seeder = new Fanout500kSeeder(jdbc);
        Fanout500kSeeder.SeedResult seed = seeder.seed(MEMBER_COUNT);

        String type = "FANOUT_CRASH_RESUME_IT";
        UUID sourceEvent = UUID.randomUUID();
        jobService.enqueue(OrgFanoutRecipientSource.SCOPE_TYPE, String.valueOf(seed.organizationId()),
                type, sourceEvent, seed.organizationId(),FanoutMessageKind.VILLAGE_EVENT_ADDED,
                    new String[]{"CMP-001 クラッシュ再開実測"},
                NotificationPriority.NORMAL, "FANOUT_CRASH_IT", null, "/x", null, true);
        NotificationFanoutJob job = jobRepository
                .findByScopeTypeAndScopeRefAndNotificationTypeAndSourceEventUuid(
                        OrgFanoutRecipientSource.SCOPE_TYPE, String.valueOf(seed.organizationId()), type, sourceEvent)
                .orElseThrow();

        // --- 1 回目: CRASH_AT_CHUNK チャンク目で INSERT 確定直後にクラッシュ（cursor は未前進のまま）。
        worker.processOne(job);

        long insertedBeforeResume = countNotifications(type);
        NotificationFanoutJob afterCrash = jobRepository.findById(job.getId()).orElseThrow();
        log.info("[crash] chunk={} 到達後クラッシュ。insertedNotifications={}（母集団未満のはず） cursor={} status={}",
                CRASH_AT_CHUNK, insertedBeforeResume, afterCrash.getCursorSubjectId(), afterCrash.getStatus());
        perf("crash_at_chunk=" + CRASH_AT_CHUNK + " inserted_before_resume=" + insertedBeforeResume
                + " cursor_after_crash=" + afterCrash.getCursorSubjectId() + " status_after_crash=" + afterCrash.getStatus());

        // 自己検証(1): クラッシュ時点で母集団未満（＝完走していない・本当に中断させられている）。
        assertThat(insertedBeforeResume).as("クラッシュ直後は母集団未満（中断が実際に起きている）")
                .isLessThan(MEMBER_COUNT);

        // --- 再開: 本物の NotificationBulkFanoutService（スパイの委譲先）で processOne を再度呼ぶ。
        //     job は 1 回目の呼び出しで in-memory cursor が「クラッシュしたチャンクの直前」まで前進済み。
        worker.processOne(job);

        long totalAfterResume = countNotifications(type);
        long distinctAfterResume = countDistinctUsers(type);
        NotificationFanoutJob finalJob = jobRepository.findById(job.getId()).orElseThrow();
        long duplicateRows = totalAfterResume - distinctAfterResume;

        log.info("[resume] total={} distinct={} duplicateRows={} status={}",
                totalAfterResume, distinctAfterResume, duplicateRows, finalJob.getStatus());
        perf("total_after_resume=" + totalAfterResume + " distinct_after_resume=" + distinctAfterResume
                + " duplicate_rows=" + duplicateRows + " final_status=" + finalJob.getStatus()
                + " member_count=" + MEMBER_COUNT);

        // (1) 中断後 inserted_count < 母集団（上のクラッシュ直後アサートと対応する冪等性チェック）。
        assertThat(insertedBeforeResume).as("(1) 中断後 inserted_count は母集団未満").isLessThan(MEMBER_COUNT);
        // (2) 再開後 notifications >= 母集団（欠落なし）。
        assertThat(totalAfterResume).as("(2) 再開後の notifications 総数は母集団以上（欠落なし）")
                .isGreaterThanOrEqualTo(MEMBER_COUNT);
        // (3) かつ <= 母集団+500（at-least-once・最大1チャンク重複の上限固定）。
        assertThat(totalAfterResume).as("(3) 重複は最大1チャンク(500件)ぶんに収まる（無限重複しない）")
                .isLessThanOrEqualTo(MEMBER_COUNT + CHUNK_SIZE);
        // 実人数（DISTINCT user_id）はちょうど母集団（欠落も混入もない）。
        assertThat(distinctAfterResume).as("DISTINCT user_id はちょうど母集団（欠落・別ユーザー混入なし）")
                .isEqualTo(MEMBER_COUNT);
        // (4) 最終 status=DONE。
        assertThat(finalJob.getStatus()).as("(4) 再開後は最終的に DONE").isEqualTo(NotificationFanoutJobStatus.DONE);
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
     * 本物の {@link NotificationBulkFanoutService#insertAndDispatchChunk} を<b>先に実行させてから</b>
     * （＝当該チャンクの INSERT を確定コミットさせてから）指定回数目の呼び出しでだけ例外を投げるスパイ。
     * {@code @Primary} で実 Bean を差し替える（{@link NotificationFanoutWorker} は型で注入されるため
     * 差し替えは worker から透過的）。
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
        // Issue #2871: ワーカーは受信者ごとに文面が異なる版を呼ぶようになったため、
        // スパイもそちらを override しないと本物が素通りしてクラッシュを模擬できない（偽 green になる）。
        public void insertAndDispatchChunk(List<RecipientMessage> rows, String notificationType,
                                           NotificationPriority priority,
                                           String sourceType, Long sourceId, NotificationScopeType scopeType,
                                           Long scopeId, String actionUrl, Long actorId, Long organizationId) {
            // 本物の処理を先に実行 → このチャンクの notifications は REQUIRES_NEW で既にコミット済みになる。
            super.insertAndDispatchChunk(rows, notificationType, priority,
                    sourceType, sourceId, scopeType, scopeId, actionUrl, actorId, organizationId);
            int n = callCount.incrementAndGet();
            if (n == crashAtChunk) {
                // ここで初めて例外を投げる＝「INSERT 確定直後・advanceCursor 到達前」のクラッシュを模す。
                throw new RuntimeException("CRASH_RESUME_IT: チャンク" + n + "確定直後の模擬クラッシュ");
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
