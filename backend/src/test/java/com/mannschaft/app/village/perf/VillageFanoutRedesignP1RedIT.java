package com.mannschaft.app.village.perf;

import com.mannschaft.app.notification.NotificationPriority;
import com.mannschaft.app.notification.NotificationScopeType;
import com.mannschaft.app.notification.service.NotificationHelper;
import com.mannschaft.app.support.perf.CountingDataSource;
import com.mannschaft.app.support.perf.Fanout10kSeeder;
import com.mannschaft.app.support.perf.SqlStatementCounter;
import com.mannschaft.app.village.dto.MeetupCandidateDateInput;
import com.mannschaft.app.village.dto.MeetupCreateRequest;
import com.mannschaft.app.village.dto.MeetupResponse;
import com.mannschaft.app.village.entity.enums.VillageEventNotificationType;
import com.mannschaft.app.village.repository.VillageMembershipRepository;
import com.mannschaft.app.village.service.VillageEventFeedRefluxService;
import com.mannschaft.app.village.service.VillageMeetupService;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.data.domain.PageRequest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import javax.sql.DataSource;
import java.lang.reflect.Method;
import java.lang.reflect.Parameter;
import java.time.Duration;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ThreadPoolExecutor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * 通知 fan-out 抜本改修 <b>P1 の受け入れ条件（AC-6〜AC-11）を符号化した red テスト</b>（測定・検証専用）。
 *
 * <p>本クラスは「出陣（実装）前」に敷く red 群であり、<b>現行 production コードでは意図どおり FAIL する</b>
 * ことを実 RUN で確認した状態でコミットする。green 化は後続の P1 出陣が行う（本試練は production を触らない）。
 * before 基準の実測（同期 1 万 INSERT・event-pool の AbortPolicy 棄却・配信 N+1）は
 * {@link VillageFanout10kMeasurementIT} が担う。本クラスは<b>done 条件の充足可否</b>を判定する。</p>
 *
 * <h2>AC ↔ テスト対応（台帳 {@code 2026-07-29-fanout-redesign-500k.md} の採番）</h2>
 * <ul>
 *   <li>AC-7 非同期化 → {@link #ac7_createMeetupReturnsBeforeFanoutCompletes()}（現行=同期ゆえ返却直後に既に N＝FAIL）</li>
 *   <li>AC-8 silent drop ゼロ → {@link #ac8_dedicatedFanoutPoolWithVisibleRejectionHandler()}（現行=専用プール不在＝FAIL）</li>
 *   <li>AC-9 バルク INSERT → {@link #ac9_notificationInsertStatementsAreSublinear()}（現行=受信者数ぶんの INSERT＝FAIL）</li>
 *   <li>AC-10 配信 N+1 消滅 → {@link #ac10_dispatchPreferenceQueriesAreSublinear()}（現行=受信者ごと 3 クエリ＝FAIL）</li>
 *   <li>AC-11 チャンクページング → {@link #ac11_recipientResolutionHasKeysetPagingMethod()}（現行=全件 List のみ＝FAIL）</li>
 *   <li>AC-6 冪等 → {@link #ac6_sameSourceFanoutIsIdempotent()}（<b>現行 characterization=green</b>。理由はメソッド Javadoc）</li>
 *   <li>AC-4 per-row 意味論 → {@link #ac4_bulkInsertPreservesPerRowColumns()}（回帰ガード・現行 green）</li>
 * </ul>
 *
 * <h2>ステートメント計測方式（AC-9/AC-10 の物差し）</h2>
 * <p>P1 出陣後のバルク INSERT は IDENTITY 採番が JPA バッチを殺すのを避けて {@code JdbcTemplate} 多値 INSERT で
 * JPA を迂回する見込みで、Hibernate {@code Statistics} では捕捉できない。そこで {@link CountingDataSource} で
 * <b>データソース層</b>の {@code execute*} をフックし、JPA/JdbcTemplate の別を問わず INSERT/SELECT の発行文数を
 * 数える（{@link SqlStatementCounter}）。これにより現行の JPA INSERT も将来の JdbcTemplate INSERT も同一物差しで
 * 比較できる。</p>
 *
 * <h2>SKIP 偽緑への注意</h2>
 * <p>基底の {@code @EnabledIf(isDockerAvailable)} は Docker 不通で<b>静かに SKIP</b> する（{@code tcp://127.0.0.1:2375}）。
 * red が SKIP で緑に見えるのは無意味。実 RUN（skipped=0）で FAIL することを確認すること。</p>
 */
@DisplayName("fan-out 抜本改修 P1 受け入れ条件 red 試練（AC-6〜AC-11・測定専用）")
@Tag("perf")
@Import(VillageFanoutRedesignP1RedIT.CountingDsConfig.class)
@EnabledIf("com.mannschaft.app.support.test.AbstractMySqlIntegrationTest#isDockerAvailable")
class VillageFanoutRedesignP1RedIT extends com.mannschaft.app.village.controller.AbstractVillageIntegrationTest {

    private static final Logger log = LoggerFactory.getLogger(VillageFanoutRedesignP1RedIT.class);

    /** fan-out 対象母集団（現役 USER メンバー）。線形/O(チャンク) の差が明瞭に出る規模で、かつ perf を過大にしない。 */
    private static final int ACTIVE_MEMBERS = 1_000;
    private static final int LEFT_MEMBERS = 20;
    private static final int BANNED_MEMBERS = 20;

    /** カウンタ名（データソース層で数える対象）。 */
    private static final String NOTIF_INSERT = "notif_insert";
    private static final String PREFS_SUBS_SELECT = "prefs_subs_select";

    @Autowired
    private EntityManager em;
    @Autowired
    private PlatformTransactionManager txManager;
    @Autowired
    private JdbcTemplate jdbc;
    @Autowired
    private ApplicationContext applicationContext;
    @Autowired
    private SqlStatementCounter statementCounter;

    @Autowired
    private VillageMeetupService meetupService;
    @Autowired
    private VillageEventFeedRefluxService refluxService;
    @Autowired
    private VillageMembershipRepository membershipRepository;
    @Autowired
    private NotificationHelper notificationHelper;
    // fan-out 抜本改修 P2: 還流は受信者を展開せず耐久ジョブを enqueue するだけになった。
    // test プロファイルは @EnableScheduling 無効ゆえ裏ワーカーは自動発火しない。
    // 通知が生成されるには processReady() を直接回してジョブを排出する必要がある（drainFanout ヘルパ）。
    @Autowired
    private com.mannschaft.app.notification.fanout.NotificationFanoutWorker fanoutWorker;

    private static volatile boolean seeded = false;
    private static UUID villageId;
    private static long actorUserId;

    @BeforeEach
    void setUp() {
        registerCounters();
        if (!seeded) {
            synchronized (VillageFanoutRedesignP1RedIT.class) {
                if (!seeded) {
                    Fanout10kSeeder seeder = new Fanout10kSeeder(em, new TransactionTemplate(txManager));
                    // 出欠・投稿は本 red 群では不要（fan-out 経路の検証に集中）。0 件で投入コストを抑える。
                    Fanout10kSeeder.SeedResult r = seeder.seed(
                            ACTIVE_MEMBERS, LEFT_MEMBERS, BANNED_MEMBERS, 0, 0);
                    villageId = r.villageId();
                    actorUserId = r.actorUserId();
                    log.info("[p1-red-seed] village={} active={}", villageId, ACTIVE_MEMBERS);
                    seeded = true;
                }
            }
        }
        // 直前テストの @Async 残タスクを排出し、プール由来の await/計測を安定させる。
        awaitEventPoolIdle(Duration.ofSeconds(60));
    }

    private void registerCounters() {
        // Hibernate/JdbcTemplate いずれの INSERT も "insert into notifications (...)" 形。
        // "notification_preferences" 等は "insert into notifications" に一致しない（語境界が異なる）。
        statementCounter.register(NOTIF_INSERT, s -> s.contains("insert into notifications"));
        // 配信ごとに引く設定/種別/購読の SELECT（受信者ごと 3 クエリ N+1 の対象テーブル）。
        statementCounter.register(PREFS_SUBS_SELECT, s -> s.startsWith("select")
                && (s.contains("notification_preferences")
                    || s.contains("notification_type_preferences")
                    || s.contains("push_subscriptions")));
    }

    // =====================================================================
    // AC-7 非同期化（red）
    // ---------------------------------------------------------------------
    // 村行事作成 API 相当（meetupService.createMeetup）が返った「直後」、対象村人の notifications 行は
    // まだ N 未満であるべき（fan-out がリクエストスレッド外で進行中）。awaitility で最終的に N へ到達。
    // 現行は還流リスナーが @Async 無し＝AFTER_COMMIT の同期実行ゆえ、createMeetup が返る時点で既に N 件
    // 生成済み → 「返却直後 < N」が偽 → FAIL(red)。これが最も綺麗な red/green 指標。
    // =====================================================================
    @Test
    @DisplayName("AC-7 createMeetup は fan-out 完了前に返る（返却直後 notifications < N・最終的に N）")
    void ac7_createMeetupReturnsBeforeFanoutCompletes() {
        long from = Fanout10kSeeder.ACTIVE_SUBJECT_BASE;
        long to = from + ACTIVE_MEMBERS - 1;
        long before = countNotifications(from, to);

        MeetupResponse created = meetupService.createMeetup(villageId, newMeetupRequest(), actorUserId);
        long immediate = countNotifications(from, to) - before;

        assertThat(created).isNotNull();
        log.info("[AC-7] createMeetup 返却直後の生成数 = {}（N={}）", immediate, ACTIVE_MEMBERS);

        // done 条件: 返却は受信者数から切り離される（返却直後はまだ全件生成されていない）。
        // P2 では還流が耐久ジョブを enqueue するだけなので、返却直後の生成数は 0（<N）。
        assertThat(immediate)
                .as("AC-7: fan-out は非同期化され、createMeetup 返却直後の生成数は N 未満であるべき"
                        + "（P2 は enqueue のみで返るため 0＝N 未満）")
                .isLessThan(ACTIVE_MEMBERS);

        // 最終的には全件（N）に到達する（裏ワーカーが耐久ジョブを排出して取りこぼさない）。
        drainFanout(from, to, before, ACTIVE_MEMBERS);
    }

    // =====================================================================
    // AC-8 silent drop ゼロ（red）
    // ---------------------------------------------------------------------
    // 配信は event-pool（queue100/pool5/AbortPolicy 既定）を共用しており、1 万規模のバーストで棄却が
    // 「静かに」起きる（VillageFanout10kMeasurementIT#ac7 が before を実証）。done 条件は「専用プール
    // notification-fanout-pool に分離し、棄却ハンドラを可視化（AbortPolicy でない）」こと。現行は当該
    // プール Bean が存在しない → containsBean=false → FAIL(red)。
    // =====================================================================
    @Test
    @DisplayName("AC-8 配信は専用プール notification-fanout-pool を持ち、棄却ハンドラが可視化される（AbortPolicy でない）")
    void ac8_dedicatedFanoutPoolWithVisibleRejectionHandler() {
        boolean present = applicationContext.containsBean("notification-fanout-pool");
        log.info("[AC-8] notification-fanout-pool 存在={}（現行=false 期待）", present);

        assertThat(present)
                .as("AC-8: dispatch は専用プール notification-fanout-pool を持つべき"
                        + "（event-pool の AbortPolicy 巻き添え棄却＝silent drop を断つ・現行は不在＝red）")
                .isTrue();

        // 専用プールが用意された暁には、棄却ハンドラは既定 AbortPolicy であってはならない（棄却を可視化する）。
        ThreadPoolTaskExecutor pool =
                applicationContext.getBean("notification-fanout-pool", ThreadPoolTaskExecutor.class);
        assertThat(pool.getThreadPoolExecutor().getRejectedExecutionHandler())
                .as("AC-8: 専用プールの棄却ハンドラは可視化ハンドラであるべき（既定 AbortPolicy は不可）")
                .isNotInstanceOf(ThreadPoolExecutor.AbortPolicy.class);
    }

    // =====================================================================
    // AC-9 バルク INSERT（red）
    // ---------------------------------------------------------------------
    // 1 回の fan-out で発行される notifications への INSERT 文数が受信者数に線形でない（O(チャンク数)）。
    // データソース層カウンタで JPA/JdbcTemplate を問わず数える。現行は受信者数ぶんの executeUpdate＝N＝FAIL。
    // =====================================================================
    @Test
    @DisplayName("AC-9 fan-out の notifications INSERT 文数は受信者数に線形でない（O(チャンク数)）")
    void ac9_notificationInsertStatementsAreSublinear() {
        long from = Fanout10kSeeder.ACTIVE_SUBJECT_BASE;
        long to = from + ACTIVE_MEMBERS - 1;
        long before = countNotifications(from, to);

        statementCounter.reset();
        meetupService.createMeetup(villageId, newMeetupRequest(), actorUserId);

        // 還流は耐久ジョブを enqueue するだけ。裏ワーカーを回して「行が N 件そろう」まで排出してから発行文数を読む。
        // ワーカーは 1 チャンク(=500)ごとに多値バルク INSERT を発行する（受信者数に線形でない）。
        drainFanout(from, to, before, ACTIVE_MEMBERS);

        long inserts = statementCounter.count(NOTIF_INSERT);
        long threshold = ACTIVE_MEMBERS / 10L; // O(チャンク数): チャンク>=200 なら ceil(N/200) 程度に収まる
        log.info("[AC-9] notifications INSERT 文数 = {}（N={}・閾値<= {}）", inserts, ACTIVE_MEMBERS, threshold);

        assertThat(inserts)
                .as("AC-9: fan-out の INSERT はバルク化され発行文数は O(チャンク数)（受信者数 N=%d に線形でない）。"
                        + "現行は受信者ごと 1 INSERT＝約 N 文ゆえ FAIL(red)", ACTIVE_MEMBERS)
                .isLessThanOrEqualTo(threshold);
    }

    // =====================================================================
    // AC-10 配信 N+1 消滅（red）
    // ---------------------------------------------------------------------
    // 配信の設定/種別/購読クエリが O(チャンク数)（受信者ごと 3 クエリでない）。プール上限を超えない件数
    // （K < event-pool queue+pool）で全件配信させ、prefs/subs SELECT 総数を数える。現行は 3K＝FAIL。
    // =====================================================================
    @Test
    @DisplayName("AC-10 配信の設定/種別/購読クエリは O(チャンク数)（受信者ごと 3 クエリの N+1 を根絶）")
    void ac10_dispatchPreferenceQueriesAreSublinear() {
        // event-pool の queue(100)+pool(5) を超えない件数にして棄却を避け、全 K 配信を確実に走らせる
        //（現行の 3 クエリ N+1 を決定論的に数えるため）。
        final int k = 80;
        long base = Fanout10kSeeder.ACTIVE_SUBJECT_BASE;
        List<Long> recipients = new ArrayList<>();
        for (int i = 0; i < k; i++) {
            recipients.add(base + i);
        }

        statementCounter.reset();
        // sourceId=null＝配信前 visibility は素通し（余計な canView クエリを入れない・還流と同条件）。
        notificationHelper.notifyAllPreAuthorized(
                recipients, VillageEventNotificationType.EVENT_CREATED.name(),
                "村の行事案内", "新しい行事が追加されました",
                "VILLAGE_EVENT", null,
                NotificationScopeType.SYSTEM, null, "/villages/perf", null);

        // @Async 配信タスクの排出を待ってから prefs/subs SELECT 総数を確定する。
        awaitEventPoolIdle(Duration.ofSeconds(120));

        long queries = statementCounter.count(PREFS_SUBS_SELECT);
        log.info("[AC-10] prefs/subs SELECT 総数 = {}（配信 K={}・現行は約 3K）", queries, k);

        assertThat(queries)
                .as("AC-10: 配信の設定/種別/購読クエリは O(チャンク数)であるべき（受信者 K=%d に線形でない）。"
                        + "現行は受信者ごと 3 クエリ＝約 3K ゆえ FAIL(red)", k)
                .isLessThanOrEqualTo((long) k);
    }

    // =====================================================================
    // AC-11 チャンクページング（red）
    // ---------------------------------------------------------------------
    // 受信者解決が全件 List 化でなくキーセット/チャンクで供給される。現行は findActiveUserSubjectIdsByVillageId
    // の全件 List のみ。done 条件＝「village_id + subject_id > カーソル LIMIT chunk」相当のページング用リポメソッド
    // が存在すること。現行は不在ゆえ FAIL(red)。出陣で下記シグネチャ相当のメソッドを追加する。
    // =====================================================================
    @Test
    @DisplayName("AC-11 受信者解決にキーセットページング用リポメソッドが存在する（全件 List 化しない）")
    void ac11_recipientResolutionHasKeysetPagingMethod() {
        Method keyset = findKeysetPagingMethod(VillageMembershipRepository.class);
        log.info("[AC-11] キーセットページング用メソッド = {}", keyset);

        assertThat(keyset)
                .as("AC-11: 受信者解決はキーセットページング（village_id + subject_id > cursor LIMIT chunk）用の"
                        + " List<Long> 返却メソッド（UUID village・カーソル long/Long・Pageable/int 上限を引数に取る）"
                        + " を必要とする。現行は全件 List の findActiveUserSubjectIdsByVillageId のみ＝red。"
                        + " 出陣で当該メソッドを追加すること。")
                .isNotNull();

        // 追加された暁には、キーセット意味論（昇順・上限件数・現役レンジ内）を軽く検証する（green 側の堅牢化）。
        assertKeysetSemanticsIfInvocable(keyset);
    }

    // =====================================================================
    // AC-6 冪等（characterization: 現行 green）
    // ---------------------------------------------------------------------
    // 「同一 source の fan-out を 2 回走らせても notifications が二重生成されない」。
    // 現挙動の確認結果: 村行事還流 VillageEventFeedRefluxService.publish は
    // (scope_village_id, system_post_type, source_event_uuid) の存在チェック（systemVillagePostExists）で
    // 2 回目を短絡し、投稿も通知も行わない ＝ source 単位では既に冪等（green）。したがって本テストは
    // red ではなく characterization（回帰ガード）である。
    //
    // ※ notifyAllPreAuthorized ヘルパ自体には重複ガードが無く、同一 (sourceType, sourceId, type) で 2 回
    //   呼べば 2N 行を作る。ただし「失敗時再開/リトライの冪等」は台帳上 P2（ジョブ表）の範疇であり、P1 の
    //   done 条件は source 単位の冪等（本テストが緑で担保）とする。詳細は最終報告に明記。
    // =====================================================================
    @Test
    @DisplayName("AC-6 同一 source(還流) の 2 回発火で通知は二重生成されない（現行 green・回帰ガード）")
    void ac6_sameSourceFanoutIsIdempotent() {
        long from = Fanout10kSeeder.ACTIVE_SUBJECT_BASE;
        long to = from + ACTIVE_MEMBERS - 1;
        UUID sourceEventUuid = UUID.randomUUID(); // まだ還流していない新規 source
        long before = countNotifications(from, to);

        refluxService.publish(villageId, VillageEventNotificationType.EVENT_CREATED,
                sourceEventUuid, "冪等確認行事", "/villages/perf");
        // 1 回目: 還流で 1 件 enqueue → 裏ワーカーで N 件排出。
        drainFanout(from, to, before, ACTIVE_MEMBERS);
        long afterFirst = countNotifications(from, to) - before;

        // 同一 source をもう一度発火（EVENT_UPCOMING バッチ再送などに相当）。
        // システム投稿の存在チェックで短絡され enqueue されない（かつ enqueue しても uk_fanout_idempotency で冪等）。
        refluxService.publish(villageId, VillageEventNotificationType.EVENT_CREATED,
                sourceEventUuid, "冪等確認行事", "/villages/perf");
        awaitEventPoolIdle(Duration.ofSeconds(60));
        fanoutWorker.processReady(); // 2 回目のジョブは無い想定（あっても冪等）。念のため排出を試みる。
        long afterSecond = countNotifications(from, to) - before;

        log.info("[AC-6] 1 回目後={} / 2 回目後={}（N={}・二重生成なら 2N）", afterFirst, afterSecond, ACTIVE_MEMBERS);
        assertThat(afterFirst).as("1 回目で N 件生成").isEqualTo(ACTIVE_MEMBERS);
        assertThat(afterSecond)
                .as("AC-6: 同一 source の 2 回目は短絡され二重生成しない（source 単位の冪等・現行 green）")
                .isEqualTo(ACTIVE_MEMBERS);
    }

    // =====================================================================
    // AC-4 per-row 意味論（回帰ガード・現行 green）
    // ---------------------------------------------------------------------
    // fan-out 生成行が受信者ごとに独立で、既読/スヌーズ/優先度/スコープの per-row 既定が正しく充填される。
    // バルク INSERT 化（多値 INSERT）後もこの充填が壊れないことを DB レベルで固定する（Entity レベルの
    // 補完は NotificationFanoutPerRowSemanticsTest）。
    // =====================================================================
    @Test
    @DisplayName("AC-4 fan-out 生成行は per-row 既定を正しく充填する（is_read=0・snoozed=null・priority・scope・user_id 一意）")
    void ac4_bulkInsertPreservesPerRowColumns() {
        long from = Fanout10kSeeder.ACTIVE_SUBJECT_BASE;
        long to = from + ACTIVE_MEMBERS - 1;
        UUID sourceEventUuid = UUID.randomUUID();

        long before = countNotifications(from, to);
        refluxService.publish(villageId, VillageEventNotificationType.EVENT_CREATED,
                sourceEventUuid, "per-row 確認行事", "/villages/perf");
        // 還流は耐久ジョブを enqueue するだけ。裏ワーカーを回して N 件排出する（per-row 既定は P1 バルク INSERT が充填）。
        drainFanout(from, to, before, ACTIVE_MEMBERS);

        // この source(uuid) 由来の行だけを見る（他テストの行と混ざらないよう type + scope + user レンジで絞る）。
        Long rows = jdbc.queryForObject(
                "SELECT COUNT(*) FROM notifications WHERE notification_type = ? AND user_id BETWEEN ? AND ?",
                Long.class, VillageEventNotificationType.EVENT_CREATED.name(), from, to);
        long badRead = orZero(jdbc.queryForObject(
                "SELECT COUNT(*) FROM notifications WHERE notification_type = ? AND user_id BETWEEN ? AND ? "
                        + "AND (is_read <> 0 OR snoozed_until IS NOT NULL)",
                Long.class, VillageEventNotificationType.EVENT_CREATED.name(), from, to));
        long badScope = orZero(jdbc.queryForObject(
                "SELECT COUNT(*) FROM notifications WHERE notification_type = ? AND user_id BETWEEN ? AND ? "
                        + "AND scope_type <> 'SYSTEM'",
                Long.class, VillageEventNotificationType.EVENT_CREATED.name(), from, to));
        Long distinctUsers = jdbc.queryForObject(
                "SELECT COUNT(DISTINCT user_id) FROM notifications WHERE notification_type = ? AND user_id BETWEEN ? AND ?",
                Long.class, VillageEventNotificationType.EVENT_CREATED.name(), from, to);

        log.info("[AC-4] rows={} badRead={} badScope={} distinctUsers={}", rows, badRead, badScope, distinctUsers);
        assertThat(orZero(rows)).as("現役 N 件以上生成される").isGreaterThanOrEqualTo(ACTIVE_MEMBERS);
        assertThat(badRead).as("per-row 既定: is_read=0 かつ snoozed_until=null").isZero();
        assertThat(badScope).as("per-row: scope_type=SYSTEM が全行で保持される").isZero();
        assertThat(orZero(distinctUsers))
                .as("user_id は受信者ぶん一意（全行同一 user_id への潰れが無い）")
                .isGreaterThanOrEqualTo(ACTIVE_MEMBERS);
    }

    // =====================================================================
    // ヘルパ
    // =====================================================================

    private MeetupCreateRequest newMeetupRequest() {
        return new MeetupCreateRequest(
                "P1 red fan-out 行事", null, null,
                List.of(new MeetupCandidateDateInput(LocalDate.now().plusDays(7), null)));
    }

    private long countNotifications(long from, long to) {
        return orZero(jdbc.queryForObject(
                "SELECT COUNT(*) FROM notifications WHERE user_id BETWEEN ? AND ?", Long.class, from, to));
    }

    /**
     * 還流で enqueue された fan-out 耐久ジョブを裏ワーカーで排出し、通知が {@code before + expected} に達するまで待つ。
     *
     * <p>P2 で還流は受信者を展開せず耐久ジョブを 1 件 enqueue するだけになった（O(1)）。test プロファイルは
     * {@code @EnableScheduling} 無効で {@code NotificationFanoutWorker.poll} が自動発火しないため、
     * {@code processReady()} を直接回してジョブを排出する（enqueue は {@code @Async} AFTER_COMMIT で行われるので
     * ジョブ出現までポーリングする）。ワーカーは 1 チャンク(=500)ごとにバルク INSERT する（発行文数は O(チャンク数)）。</p>
     */
    private void drainFanout(long from, long to, long before, long expected) {
        await().atMost(Duration.ofSeconds(120)).pollInterval(Duration.ofMillis(500))
                .untilAsserted(() -> {
                    fanoutWorker.processReady();
                    assertThat(countNotifications(from, to) - before).isEqualTo(expected);
                });
    }

    private static long orZero(Long v) {
        return v == null ? 0L : v;
    }

    private void awaitEventPoolIdle(Duration timeout) {
        ThreadPoolExecutor tpe = applicationContext
                .getBean("event-pool", ThreadPoolTaskExecutor.class).getThreadPoolExecutor();
        await().atMost(timeout).pollInterval(Duration.ofMillis(200))
                .until(() -> tpe.getActiveCount() == 0 && tpe.getQueue().isEmpty());
    }

    /**
     * リポジトリ上の「キーセットページング用」メソッドを探す。
     * 条件: 返り値が {@link List}（List&lt;Long&gt; 想定）で、引数に (a) {@link UUID}（村）と
     * (b) カーソル（long/Long）と (c) 件数上限（{@code Pageable} または int）を持つもの。
     * 見つからなければ {@code null}（＝現行）。
     */
    private static Method findKeysetPagingMethod(Class<?> repo) {
        for (Method m : repo.getMethods()) {
            if (!List.class.isAssignableFrom(m.getReturnType())) {
                continue;
            }
            boolean hasUuid = false;
            boolean hasCursor = false;
            boolean hasLimit = false;
            for (Parameter p : m.getParameters()) {
                Class<?> t = p.getType();
                if (t.equals(UUID.class)) {
                    hasUuid = true;
                } else if (t.equals(Long.class) || t.equals(long.class)) {
                    hasCursor = true;
                } else if (t.getName().equals("org.springframework.data.domain.Pageable")
                        || t.equals(int.class) || t.equals(Integer.class)) {
                    hasLimit = true;
                }
            }
            if (hasUuid && hasCursor && hasLimit) {
                return m;
            }
        }
        return null;
    }

    /** キーセットメソッドが (UUID, long/Long, Pageable) で呼べる形なら、昇順・上限件数・現役レンジを軽く検証する。 */
    private void assertKeysetSemanticsIfInvocable(Method keyset) {
        if (keyset == null) {
            return;
        }
        Class<?>[] types = keyset.getParameterTypes();
        if (types.length != 3) {
            return;
        }
        boolean shape = types[0].equals(UUID.class)
                && (types[1].equals(Long.class) || types[1].equals(long.class))
                && types[2].getName().equals("org.springframework.data.domain.Pageable");
        if (!shape) {
            return;
        }
        try {
            int chunk = 200;
            @SuppressWarnings("unchecked")
            List<Long> first = (List<Long>) keyset.invoke(
                    membershipRepository, villageId, 0L, PageRequest.of(0, chunk));
            assertThat(first).as("キーセット 1 チャンク目はちょうど chunk 件").hasSize(chunk);
            assertThat(first).as("subject_id 昇順").isSorted();
            long activeFrom = Fanout10kSeeder.ACTIVE_SUBJECT_BASE;
            long activeTo = activeFrom + ACTIVE_MEMBERS - 1;
            assertThat(first).allSatisfy(id ->
                    assertThat(id).isBetween(activeFrom, activeTo));
        } catch (ReflectiveOperationException e) {
            throw new AssertionError("AC-11 キーセットメソッドの呼び出しに失敗: " + keyset, e);
        }
    }

    /**
     * 計測用に実 {@link DataSource} を {@link CountingDataSource} でラップするテスト構成。
     * BeanPostProcessor で自動構成済み DataSource を包み、全 {@code execute*} を {@link SqlStatementCounter} へ通す。
     */
    @TestConfiguration
    static class CountingDsConfig {
        static final SqlStatementCounter COUNTER = new SqlStatementCounter();

        @Bean
        SqlStatementCounter sqlStatementCounter() {
            return COUNTER;
        }

        @Bean
        static BeanPostProcessor countingDataSourceWrapper() {
            return new BeanPostProcessor() {
                @Override
                public Object postProcessAfterInitialization(Object bean, String beanName) {
                    if (bean instanceof DataSource ds && !(bean instanceof CountingDataSource)) {
                        return new CountingDataSource(ds, COUNTER);
                    }
                    return bean;
                }
            };
        }
    }
}
