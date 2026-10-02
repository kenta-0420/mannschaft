package com.mannschaft.app.village.perf;

import com.mannschaft.app.notification.NotificationPriority;
import com.mannschaft.app.notification.NotificationScopeType;
import com.mannschaft.app.notification.service.NotificationHelper;
import com.mannschaft.app.notification.service.NotificationPreferenceService;
import com.mannschaft.app.notification.service.PushSubscriptionService;
import com.mannschaft.app.support.perf.Fanout10kSeeder;
import com.mannschaft.app.support.test.AbstractMySqlIntegrationTest;
import com.mannschaft.app.timeline.dto.PostResponse;
import com.mannschaft.app.timeline.entity.TimelinePostEntity;
import com.mannschaft.app.timeline.repository.TimelinePostRepository;
import com.mannschaft.app.timeline.service.TimelinePostService;
import com.mannschaft.app.village.dto.MeetupCandidateDateInput;
import com.mannschaft.app.village.dto.MeetupCreateRequest;
import com.mannschaft.app.village.dto.MeetupResponse;
import com.mannschaft.app.village.entity.VillageMeetupAttendanceEntity;
import com.mannschaft.app.village.entity.enums.VillageMeetupAttendanceStatus;
import com.mannschaft.app.village.repository.VillageMeetupAttendanceRepository;
import com.mannschaft.app.village.repository.VillageMembershipRepository;
import com.mannschaft.app.village.service.VillageMeetupService;
import jakarta.persistence.EntityManager;
import jakarta.persistence.EntityManagerFactory;
import org.hibernate.SessionFactory;
import org.hibernate.stat.Statistics;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationContext;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Duration;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ThreadPoolExecutor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * β4（ペスカドーラ町田・1万人規模）に向けた村 fan-out コストの<b>実測 IT</b>（測定＋特性化のみ）。
 *
 * <p><b>射程は測定と文書化に限る</b>。production コードの挙動は一切変更しない。ここで摘出した
 * 欠陥（同期1万 INSERT のブロック・dispatch の AbortPolicy 棄却・配信 N+1）は後続 fix 戦役の
 * before 基準として {@code docs/load-test/fanout-10k/findings.md} に転記する（AC-10）。</p>
 *
 * <h2>実行方法（CI smoke からは分離）</h2>
 * <p>本クラスは {@code @Tag("perf")} で通常の {@code test} タスクから除外される。実行は専用タスク:</p>
 * <pre>{@code
 *   cd backend
 *   ./gradlew perfTest -Pmax.parallel.forks=1
 * }</pre>
 *
 * <h2>SKIP 偽緑への注意</h2>
 * <p>基底 {@link AbstractMySqlIntegrationTest} の {@code @EnabledIf(isDockerAvailable)} は Docker 不通で
 * <b>静かに SKIP</b> する。測定値を得るには実 RUN（"Tests run: N", skipped=0）を確認すること。SKIP は
 * 「測定未実施」であって偽の数値を作ってはならない。</p>
 *
 * <h2>Valkey モックの割り切り</h2>
 * <p>基底は {@code StringRedisTemplate} をモック化するため、WebSocket/Valkey のリアルタイム配信の実測は
 * 範囲外。本 IT は「DB INSERT の fan-out」「dispatch プールの飽和棄却」「購読/設定クエリの N+1」を実測する。</p>
 */
@DisplayName("村 fan-out 1万人規模 実測IT（β4前・測定専用）")
@Tag("perf")
@EnabledIf("com.mannschaft.app.support.test.AbstractMySqlIntegrationTest#isDockerAvailable")
class VillageFanout10kMeasurementIT extends com.mannschaft.app.village.controller.AbstractVillageIntegrationTest {

    private static final Logger log = LoggerFactory.getLogger(VillageFanout10kMeasurementIT.class);

    /** fan-out 対象母集団（現役 USER メンバー）。 */
    private static final int ACTIVE_MEMBERS = 10_000;
    /** 退村済み（対象境界の外）。 */
    private static final int LEFT_MEMBERS = 30;
    /** BAN 済み（対象境界の外）。 */
    private static final int BANNED_MEMBERS = 30;
    /** CONFIRMED 寄合の出欠行数。 */
    private static final int ATTENDANCES = 10_000;
    /** 村フィード（scope=VILLAGE）投稿数。 */
    private static final int VILLAGE_POSTS = 10_000;

    @Autowired
    private EntityManager em;
    @Autowired
    private EntityManagerFactory emf;
    @Autowired
    private PlatformTransactionManager txManager;
    @Autowired
    private JdbcTemplate jdbc;
    @Autowired
    private ApplicationContext applicationContext;

    @Autowired
    private VillageMeetupService meetupService;
    @Autowired
    private VillageMembershipRepository membershipRepository;
    @Autowired
    private VillageMeetupAttendanceRepository attendanceRepository;
    @Autowired
    private TimelinePostRepository timelinePostRepository;
    @Autowired
    private TimelinePostService timelinePostService;
    @Autowired
    private NotificationHelper notificationHelper;
    @Autowired
    private NotificationPreferenceService preferenceService;
    @Autowired
    private PushSubscriptionService pushSubscriptionService;

    // 合成データは高コストなためクラス内で 1 度だけ投入する（測定値はメソッドごとに独立）。
    private static volatile boolean seeded = false;
    private static UUID villageId;
    private static UUID confirmedMeetupId;
    private static long actorUserId;

    private Statistics statistics;

    @BeforeEach
    void setUp() {
        statistics = emf.unwrap(SessionFactory.class).getStatistics();
        statistics.setStatisticsEnabled(true);
        if (seeded) {
            return;
        }
        synchronized (VillageFanout10kMeasurementIT.class) {
            if (seeded) {
                return;
            }
            long t0 = System.nanoTime();
            Fanout10kSeeder seeder = new Fanout10kSeeder(em, new TransactionTemplate(txManager));
            Fanout10kSeeder.SeedResult r = seeder.seed(
                    ACTIVE_MEMBERS, LEFT_MEMBERS, BANNED_MEMBERS, ATTENDANCES, VILLAGE_POSTS);
            villageId = r.villageId();
            confirmedMeetupId = r.confirmedMeetupId();
            actorUserId = r.actorUserId();
            long ms = (System.nanoTime() - t0) / 1_000_000;
            log.info("[fanout-seed] 投入完了: village={} active={} left={} banned={} attendances={} posts={} ({} ms)",
                    villageId, ACTIVE_MEMBERS, LEFT_MEMBERS, BANNED_MEMBERS, ATTENDANCES, VILLAGE_POSTS, ms);
            perf("SEED_ms=" + ms + " active=" + ACTIVE_MEMBERS + " left=" + LEFT_MEMBERS
                    + " banned=" + BANNED_MEMBERS + " attendances=" + ATTENDANCES + " posts=" + VILLAGE_POSTS);
            seeded = true;
        }
    }

    // =====================================================================
    // AC-1 / AC-3: 受信者解決の網羅と対象境界（退村・BAN の除外）
    // =====================================================================
    @Test
    @DisplayName("AC-1/AC-3 現役 USER 受信者解決はちょうど1万件・退村/BANは除外される")
    void ac1_ac3_activeRecipientResolutionExcludesLeftAndBanned() {
        List<Long> recipients = membershipRepository.findActiveUserSubjectIdsByVillageId(villageId);

        assertThat(recipients).hasSize(ACTIVE_MEMBERS);
        // 対象境界（AC-3）: 退村・BAN の subject_id は 1 件も混じらない。
        assertThat(recipients).allMatch(id ->
                id >= Fanout10kSeeder.ACTIVE_SUBJECT_BASE
                        && id < Fanout10kSeeder.ACTIVE_SUBJECT_BASE + ACTIVE_MEMBERS);
        assertThat(recipients).noneMatch(id -> id >= Fanout10kSeeder.LEFT_SUBJECT_BASE);
        log.info("[AC-1/AC-3] findActiveUserSubjectIdsByVillageId={} 件（退村/BAN 除外を確認）", recipients.size());
        perf("AC1_recipients=" + recipients.size() + " AC3_left_banned_excluded=true");
    }

    // =====================================================================
    // AC-2 / AC-6: 村行事作成の同期 fan-out（1万 INSERT）— 生成件数と壁時計
    // =====================================================================
    @Test
    @DisplayName("AC-2/AC-6 村行事1件作成で通知1万件生成・境界除外（fan-out 抜本改修 P1 後は @Async・API 応答は fan-out と非結合）")
    void ac2_ac6_fanoutGeneratesExactlyActiveMembers() {
        long activeFrom = Fanout10kSeeder.ACTIVE_SUBJECT_BASE;
        long activeTo = activeFrom + ACTIVE_MEMBERS - 1;
        long boundaryFrom = Fanout10kSeeder.LEFT_SUBJECT_BASE;
        long boundaryTo = Fanout10kSeeder.BANNED_SUBJECT_BASE + BANNED_MEMBERS - 1;

        long notifBefore = countNotifications(activeFrom, activeTo);

        // event-pool（還流リスナー @Async の実行プール）の end-to-end 稼働も参考記録する。
        ThreadPoolExecutor tpe = applicationContext
                .getBean("event-pool", ThreadPoolTaskExecutor.class).getThreadPoolExecutor();
        long completedBefore = tpe.getCompletedTaskCount();

        // AC-7（fan-out 抜本改修 P1）: 還流リスナーは @Async 化され、createMeetup の応答は fan-out の完了を
        // 待たずに即座に返る。この壁時計は「API 応答レイテンシ」であり、受信者数からは切り離されている
        // （before は同期還流でこの時間に 1万 INSERT が乗っていた）。
        MeetupCreateRequest req = new MeetupCreateRequest(
                "β4 fan-out 実測行事", null, null,
                List.of(new MeetupCandidateDateInput(LocalDate.now().plusDays(7), null)));
        long t0 = System.nanoTime();
        MeetupResponse created = meetupService.createMeetup(villageId, req, actorUserId);
        long apiReturnMs = (System.nanoTime() - t0) / 1_000_000;

        assertThat(created).isNotNull();

        // AC-2: fan-out は非同期で進むため、現役1万件の生成完了を await してから件数を確定する
        //（取りこぼしゼロ＝ちょうど1万件という正しさ不変条件は @Async 化後も保たれる）。
        await().atMost(Duration.ofSeconds(180)).pollInterval(Duration.ofMillis(500))
                .until(() -> countNotifications(activeFrom, activeTo) - notifBefore >= ACTIVE_MEMBERS);
        long generated = countNotifications(activeFrom, activeTo) - notifBefore;
        // AC-3 境界: 退村/BAN の user_id には 1 件も通知されない。
        long boundaryNotifs = countNotifications(boundaryFrom, boundaryTo);

        // event-pool（還流タスク）の end-to-end 完了数（参考）。実際の配信タスクは
        // notification-fanout-pool へ分離されたため、ここには還流タスク＋監査分が計上される。
        await().atMost(Duration.ofSeconds(120)).pollInterval(Duration.ofMillis(500))
                .until(() -> tpe.getActiveCount() == 0 && tpe.getQueue().isEmpty());
        long eventPoolCompleted = tpe.getCompletedTaskCount() - completedBefore;

        log.info("[AC-7] 村行事作成 API 応答レイテンシ = {} ms（@Async 化で fan-out と非結合・受信者数に非依存）", apiReturnMs);
        log.info("[AC-2] notifications 生成数 = {} 件（現役1万に一致）／境界(退村+BAN)への通知 = {} 件", generated, boundaryNotifs);
        log.info("[参考] event-pool end-to-end 完了タスク数 = {}（還流タスク＋監査分。配信は notification-fanout-pool へ分離）",
                eventPoolCompleted);
        perf("AC7_api_return_ms=" + apiReturnMs + " AC2_generated=" + generated
                + " AC3_boundary_notifs=" + boundaryNotifs
                + " event_pool_completed=" + eventPoolCompleted);

        assertThat(generated).as("現役1万人ぶんの通知がちょうど1万件生成される").isEqualTo(ACTIVE_MEMBERS);
        assertThat(boundaryNotifs).as("退村/BAN には通知されない（対象境界）").isZero();
    }

    // =====================================================================
    // AC-7: dispatch プール（event-pool）の AbortPolicy 棄却を決定的に実証
    // ---------------------------------------------------------------------
    // 1万件 dispatch を真のバースト（同時投入）にさらすと queue(100)+pool(5)=~105 で頭打ちになり、
    // 以降は RejectedExecutionException で「静かに」棄却される（明示 rejection handler 無し＝既定 AbortPolicy）。
    // end-to-end 経路は producer の INSERT レイテンシに配信投入がペーシングされるため drop 数が
    // タイミング依存になる。ここでは production の event-pool Bean そのものへ 1万タスクを一斉投入し、
    // 実際の棄却件数を決定的に数える（＝β4 の同時多発配信で必ず起きる欠陥の実証）。
    // =====================================================================
    @Test
    @DisplayName("AC-7 event-pool は1万バーストで queue+pool 上限(~105)を超えた分を AbortPolicy で棄却する")
    void ac7_dispatchDropUnderBurst() throws InterruptedException {
        ThreadPoolTaskExecutor eventPool =
                applicationContext.getBean("event-pool", ThreadPoolTaskExecutor.class);
        ThreadPoolExecutor tpe = eventPool.getThreadPoolExecutor();

        // 事前に idle であることを担保（直前テストの残タスクを待つ）。
        await().atMost(Duration.ofSeconds(60)).pollInterval(Duration.ofMillis(200))
                .until(() -> tpe.getActiveCount() == 0 && tpe.getQueue().isEmpty());

        int queueCapacity = tpe.getQueue().remainingCapacity() + tpe.getQueue().size();
        int maxPool = tpe.getMaximumPoolSize();

        final int burst = ACTIVE_MEMBERS; // 1万件を一斉投入
        final java.util.concurrent.CountDownLatch hold = new java.util.concurrent.CountDownLatch(1);
        int accepted = 0;
        int rejected = 0;
        try {
            for (int i = 0; i < burst; i++) {
                try {
                    // 受理タスクは latch で待機させ、pool を意図的に飽和状態に保つ。
                    tpe.execute(() -> {
                        try {
                            hold.await();
                        } catch (InterruptedException e) {
                            Thread.currentThread().interrupt();
                        }
                    });
                    accepted++;
                } catch (java.util.concurrent.RejectedExecutionException rex) {
                    rejected++; // AbortPolicy による棄却（明示 handler 無し＝既定）
                }
            }
        } finally {
            hold.countDown(); // 受理タスクを解放
            await().atMost(Duration.ofSeconds(60)).pollInterval(Duration.ofMillis(200))
                    .until(() -> tpe.getActiveCount() == 0 && tpe.getQueue().isEmpty());
        }

        boolean isAbortPolicy = tpe.getRejectedExecutionHandler() instanceof ThreadPoolExecutor.AbortPolicy;

        log.info("[AC-7] event-pool 構成: queueCapacity={} maxPool={} rejectedHandler={}",
                queueCapacity, maxPool, tpe.getRejectedExecutionHandler().getClass().getSimpleName());
        log.info("[AC-7] 1万バースト投入 → 受理={} 件 / 棄却(AbortPolicy)={} 件（β4の同時多発配信で失われる通知数の桁）",
                accepted, rejected);
        perf("AC7_burst=" + burst + " queueCapacity=" + queueCapacity + " maxPool=" + maxPool
                + " handler=" + tpe.getRejectedExecutionHandler().getClass().getSimpleName()
                + " accepted=" + accepted + " rejected=" + rejected);

        assertThat(isAbortPolicy)
                .as("event-pool の rejection handler は明示指定が無く既定の AbortPolicy（＝棄却は握り潰される）")
                .isTrue();
        assertThat(rejected)
                .as("1万バーストでは queue+pool 上限を超えた分が AbortPolicy で棄却される（正しさ寄りの欠陥）")
                .isGreaterThan(0);
        // 受理はおおむね queue(100)+pool(5) 近傍に頭打ちになる（環境で±あるため寛容上限で回帰検出）。
        assertThat(accepted)
                .as("受理は queue+pool 上限近傍で頭打ち（1万をさばけない）")
                .isLessThan(queueCapacity + maxPool + 50);
    }

    // =====================================================================
    // AC-4: listAttendances のクエリ数はページサイズ・総行数に依存しない（O(1)・N+1なし）
    // =====================================================================
    @Test
    @DisplayName("AC-4 出欠1万件でも listAttendances のクエリ数はページサイズ独立（N+1なし・健全の回帰ガード）")
    void ac4_listAttendancesQueryCountIsPageSizeIndependent() {
        // 生リポジトリ（Page）: SELECT + COUNT の 2 発で、総行数に依存しない。
        long q20Repo = measureQueries(() ->
                attendanceRepository.findByMeetupIdOrderByCreatedAtAsc(confirmedMeetupId, PageRequest.of(0, 20)));
        long q100Repo = measureQueries(() ->
                attendanceRepository.findByMeetupIdOrderByCreatedAtAsc(confirmedMeetupId, PageRequest.of(0, 100)));

        Page<VillageMeetupAttendanceEntity> page20 =
                attendanceRepository.findByMeetupIdOrderByCreatedAtAsc(confirmedMeetupId, PageRequest.of(0, 20));
        Page<VillageMeetupAttendanceEntity> page100 =
                attendanceRepository.findByMeetupIdOrderByCreatedAtAsc(confirmedMeetupId, PageRequest.of(0, 100));
        assertThat(page20.getContent()).hasSize(20);
        assertThat(page100.getContent()).hasSize(100);
        assertThat(page20.getTotalElements()).isEqualTo(ATTENDANCES);

        // サービス経由（表示名バッチ解決込み）でもページサイズに依存しない一定クエリ数であること。
        long q20Svc = measureQueries(() ->
                meetupService.listAttendances(villageId, confirmedMeetupId, actorUserId, PageRequest.of(0, 20)));
        long q100Svc = measureQueries(() ->
                meetupService.listAttendances(villageId, confirmedMeetupId, actorUserId, PageRequest.of(0, 100)));

        log.info("[AC-4] listAttendances クエリ数: repo(20)={} repo(100)={} / service(20)={} service(100)={}（総行数1万・ページサイズに非依存）",
                q20Repo, q100Repo, q20Svc, q100Svc);
        perf("AC4_repo_q20=" + q20Repo + " repo_q100=" + q100Repo
                + " svc_q20=" + q20Svc + " svc_q100=" + q100Svc + " total_rows=" + ATTENDANCES);

        assertThat(q20Repo).as("リポジトリのクエリ数はページサイズ非依存").isEqualTo(q100Repo);
        assertThat(q20Svc).as("サービスのクエリ数はページサイズ非依存（N+1なし）").isEqualTo(q100Svc);
    }

    // =====================================================================
    // AC-5 / AC-9: 村フィードは総投稿数に依存せず1クエリ・先頭ページ／read レイテンシ実測
    // =====================================================================
    @Test
    @DisplayName("AC-5/AC-9 投稿1万件でも村フィードは1クエリ・先頭20件／getFeed レイテンシ実測")
    void ac5_ac9_villageFeedSingleQueryAndLatency() {
        int feedSize = 20;

        long feedQueries = measureQueries(() -> {
            List<TimelinePostEntity> posts =
                    timelinePostRepository.findFeedByVillageId(villageId, PageRequest.of(0, feedSize));
            assertThat(posts).hasSize(feedSize);
        });

        // AC-9: サービス経由の read レイテンシ（メンバー検証＋enrich 込み）を実測。
        long t0 = System.nanoTime();
        List<PostResponse> feed = timelinePostService.getFeed("VILLAGE", 0L, villageId, feedSize, actorUserId);
        long readMs = (System.nanoTime() - t0) / 1_000_000;
        assertThat(feed).hasSize(feedSize);

        log.info("[AC-5] findFeedByVillageId 発行クエリ数 = {}（総投稿1万・先頭{}件・filesort）", feedQueries, feedSize);
        log.info("[AC-9] getFeed（村フィード read）レイテンシ = {} ms（総投稿1万・先頭ページ）", readMs);
        perf("AC5_feed_queries=" + feedQueries + " feed_size=" + feedSize
                + " total_posts=" + VILLAGE_POSTS + " AC9_getfeed_latency_ms=" + readMs);

        // AC-5: 母集合が1万でもフィード取得は 1 クエリ（N+1 なし・先頭ページ固定）。
        assertThat(feedQueries).as("村フィード取得はページ母集合に依存せず 1 クエリ").isEqualTo(1);
    }

    // =====================================================================
    // AC-8: 配信あたりの購読/設定クエリ数（N+1）
    // =====================================================================
    @Test
    @DisplayName("AC-8 配信1件あたりの購読/設定クエリ数（N+1）を実測・記録")
    void ac8_perDispatchQueryCountN1() {
        long u = Fanout10kSeeder.ACTIVE_SUBJECT_BASE;

        // dispatch が受信者ごとに発行する 3 クエリ（スコープ設定・種別設定・購読一覧）を再現して数える。
        long perDispatch = measureQueries(() -> {
            preferenceService.isNotificationEnabled(u, NotificationScopeType.SYSTEM.name(), null);
            preferenceService.isTypeEnabled(u, "EVENT_CREATED");
            pushSubscriptionService.listSubscriptions(u);
        });

        long projected = perDispatch * ACTIVE_MEMBERS;
        log.info("[AC-8] 配信1件あたりクエリ数 = {} → 1万配信で {} クエリ（設定/種別/購読の受信者ごと N+1）",
                perDispatch, projected);
        perf("AC8_per_dispatch_queries=" + perDispatch + " projected_10k=" + projected);

        assertThat(perDispatch)
                .as("配信1件あたり 1 クエリ超（受信者ごとに設定・種別・購読を引く N+1）")
                .isGreaterThan(1);
    }

    // =====================================================================
    // AC-11: fan-out 途中で1件 INSERT が失敗しても残りが継続する（best-effort try/catch）
    // =====================================================================
    @Test
    @DisplayName("AC-11 fan-out 途中の1件失敗でも残りの配信は継続する（best-effort 特性化）")
    void ac11_bestEffortContinuesAfterOneFailure() {
        final long base = 930_000_000L;
        final int valid = 50;

        List<Long> recipients = new ArrayList<>();
        for (int i = 0; i < valid; i++) {
            recipients.add(base + i);
        }
        // 中央に null 受信者を差し込む（createNotificationPreAuthorized で NOT NULL 制約違反＝1件だけ失敗）。
        recipients.add(valid / 2, null);

        long before = countNotifications(base, base + valid - 1);

        // 本メソッドは @Transactional でないため、各 createNotificationPreAuthorized は独立した REQUIRED tx。
        // null 受信者はその tx 内でのみロールバックし、残りの正当な受信者はコミットされる（best-effort）。
        notificationHelper.notifyAllPreAuthorized(
                recipients, "EVENT_CREATED", NotificationPriority.NORMAL,
                "AC-11 best-effort", "1件失敗しても継続する",
                "PERF_AC11_SRC", 777_001L,
                NotificationScopeType.SYSTEM, null, "/villages/perf", null);

        long after = countNotifications(base, base + valid - 1);
        long created = after - before;

        log.info("[AC-11] 受信者{}件（うち null 1件）を投入 → 通知作成 {} 件（失敗1件を飛ばして残りは継続）",
                recipients.size(), created);
        assertThat(created).as("null 受信者1件が失敗しても、残り50件の通知は作成される").isEqualTo(valid);
    }

    // =====================================================================
    // ヘルパ
    // =====================================================================

    /** {@code runnable} 実行前後で Hibernate が発行した JDBC prepared statement 数の差分を返す。 */
    private long measureQueries(Runnable runnable) {
        statistics.clear();
        long before = statistics.getPrepareStatementCount();
        runnable.run();
        return statistics.getPrepareStatementCount() - before;
    }

    /**
     * 測定値を <b>ASCII の key=value</b> で {@code System.out} に直接出力する（logback を経由しない）。
     *
     * <p>本番 logback（コンテキスト起動後にコンソール閾値が WARN 相当）は測定用 {@code log.info} を
     * 握り潰し、さらに日本語ログは文字化けして JUnit XML から読めなくなる。{@code System.out.println} は
     * logback を迂回して gradle の {@code <system-out>} に確実に取り込まれ、ASCII なら文字化けもしない。
     * これにより「捏造禁止・実RUNの実測値のみ」を機械可読な形で担保する（{@code PERF_MEASURE } 前置）。</p>
     */
    private static void perf(String kv) {
        System.out.println("PERF_MEASURE " + kv);
    }

    private long countNotifications(long userIdFrom, long userIdTo) {
        Long c = jdbc.queryForObject(
                "SELECT COUNT(*) FROM notifications WHERE user_id BETWEEN ? AND ?",
                Long.class, userIdFrom, userIdTo);
        return c == null ? 0L : c;
    }
}
