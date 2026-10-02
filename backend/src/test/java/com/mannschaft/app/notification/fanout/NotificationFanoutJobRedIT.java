package com.mannschaft.app.notification.fanout;

import com.mannschaft.app.notification.NotificationPriority;
import com.mannschaft.app.support.perf.CountingDataSource;
import com.mannschaft.app.support.perf.SqlStatementCounter;
import com.mannschaft.app.support.test.AbstractMySqlIntegrationTest;
import jakarta.persistence.EntityManager;
import jakarta.persistence.EntityManagerFactory;
import jakarta.persistence.LockModeType;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import javax.sql.DataSource;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 通知 fan-out 抜本改修 <b>P2（耐久ジョブ表＋裏ワーカー）の受け入れ条件を符号化した red 試練</b>。
 *
 * <p>MySQL 依存機能（複合ユニーク・{@code FOR UPDATE SKIP LOCKED}・カーソル永続化）を実測するため
 * {@link AbstractMySqlIntegrationTest} 基底で回す。実装本体（enqueue／ワーカー）は<b>未実装（no-op／例外）</b>
 * であり、以下の AC が現行で FAIL することを実 RUN（skipped=0）で確認した状態でコミットする。green 化は
 * P2 出陣が行う。</p>
 *
 * <h2>AC ↔ テスト対応</h2>
 * <ul>
 *   <li>AC-1 冪等 → {@link #ac1_enqueueIsIdempotentByUniqueKey()}（現行 no-op enqueue＝0件＝red）</li>
 *   <li>AC-2 クラッシュ再開 → {@link #ac2_workerResumesFromCursorExactlyOnce()}（worker 未実装＝red）</li>
 *   <li>AC-3 リトライ→DEAD_LETTER → {@link #ac3_retriesThenDeadLetters()}（worker 未実装＝red）</li>
 *   <li>AC-4 SKIP LOCKED → {@link #ac4_findReadySkipsLockedJob()}（findReady は実装済＝現行 green・infra 証明）</li>
 *   <li>AC-7 enqueue O(1) → {@link #ac7_enqueueIsSingleInsert()}（現行 no-op enqueue＝0 INSERT＝red）</li>
 * </ul>
 *
 * <h2>SKIP 偽緑への注意</h2>
 * <p>基底 {@code @EnabledIf(isDockerAvailable)} は Docker 不通で<b>静かに SKIP</b> する。red が SKIP で緑に
 * 見えるのは無意味。実 RUN（skipped=0）で FAIL を確認すること。</p>
 */
@DisplayName("fan-out 抜本改修 P2 受け入れ条件 red 試練（耐久ジョブ表＋裏ワーカー）")
@Import({NotificationFanoutJobRedIT.TestRecipientSourceConfig.class, NotificationFanoutJobRedIT.CountingDsConfig.class})
@EnabledIf("com.mannschaft.app.support.test.AbstractMySqlIntegrationTest#isDockerAvailable")
class NotificationFanoutJobRedIT extends AbstractMySqlIntegrationTest {

    private static final Logger log = LoggerFactory.getLogger(NotificationFanoutJobRedIT.class);

    /** テスト用受信者ソースの scope_type（村実装に依存しない合成ソース）。 */
    static final String TEST_SCOPE = "TEST_IT_SCOPE";
    /** この scope_id を渡すと受信者解決が失敗する（AC-3 の配信失敗シミュレーション）。 */
    static final long FAILING_SCOPE_ID = 9_003L;
    /** 1 scope あたりの合成受信者数。 */
    static final int RECIPIENTS_PER_SCOPE = 50;
    /** カウンタ名。 */
    private static final String JOB_INSERT = "fanout_job_insert";

    @Autowired
    private NotificationFanoutJobService jobService;
    @Autowired
    private NotificationFanoutJobRepository jobRepository;
    @Autowired
    private NotificationFanoutWorker worker;
    @Autowired
    private JdbcTemplate jdbc;
    @Autowired
    private EntityManagerFactory emf;
    @Autowired
    private PlatformTransactionManager txManager;
    @Autowired
    private SqlStatementCounter statementCounter;

    /** scope_id に対応する合成受信者 subject_id の下端（＝scopeId*1000）。 */
    static long recipientBase(long scopeId) {
        return scopeId * 1000L;
    }

    // =====================================================================
    // AC-1 冪等: 同一キーの2回 enqueue でジョブ行はちょうど1件（DBユニーク）
    // =====================================================================
    @Test
    @DisplayName("AC-1 同一(scope,type,source_event_uuid)の2回 enqueue はジョブ1件（DBユニーク・現行 no-op=red）")
    void ac1_enqueueIsIdempotentByUniqueKey() {
        long scopeId = 9_001L;
        String type = "FANOUT_IT_AC1";
        UUID sourceEvent = UUID.randomUUID();

        jobService.enqueue(TEST_SCOPE, String.valueOf(scopeId), type, sourceEvent, null,FanoutMessageKind.VILLAGE_EVENT_ADDED,
                    new String[]{"AC-1 冪等"}, NotificationPriority.NORMAL, "FANOUT_IT", null, "/x", null);
        jobService.enqueue(TEST_SCOPE, String.valueOf(scopeId), type, sourceEvent, null,FanoutMessageKind.VILLAGE_EVENT_ADDED,
                    new String[]{"AC-1 冪等"}, NotificationPriority.NORMAL, "FANOUT_IT", null, "/x", null);

        Optional<NotificationFanoutJob> found = jobRepository
                .findByScopeTypeAndScopeRefAndNotificationTypeAndSourceEventUuid(
                        TEST_SCOPE, String.valueOf(scopeId), type, sourceEvent);

        log.info("[AC-1] enqueue×2 後のジョブ存在={}（present=1件・DBユニーク）", found.isPresent());
        assertThat(found)
                .as("AC-1: 同一キーの2回 enqueue でジョブ行はちょうど1件（uk_fanout_idempotency）。"
                        + "現行は enqueue が no-op ゆえ 0 件＝FAIL(red)")
                .isPresent();
    }

    // =====================================================================
    // AC-2 クラッシュ再開: cursor 途中(k)から再開し、最終 notifications はちょうどN・job=DONE
    // ---------------------------------------------------------------------
    // pre-crash で先頭 k 件の通知が既にコミット済み（cursor=k）という状態を作り、worker を再開させる。
    // green: cursor より後の (N-k) 件のみ生成し、合計 N・欠落も重複もなし・job=DONE。
    // 現行: processReady 未実装（UnsupportedOperationException）ゆえ FAIL(red)。
    // =====================================================================
    @Test
    @DisplayName("AC-2 worker は cursor から再開し notifications はちょうどN・重複なし・job=DONE（worker 未実装=red）")
    void ac2_workerResumesFromCursorExactlyOnce() {
        long scopeId = 9_002L;
        String type = "FANOUT_IT_AC2";
        long base = recipientBase(scopeId);           // 9_002_000
        long from = base + 1;
        long to = base + RECIPIENTS_PER_SCOPE;         // N 人 = [from, to]
        int k = 20;                                    // pre-crash で処理済みの件数

        // pre-crash: 先頭 k 件の通知は既にコミット済み（cursor がここまで進んでいた）。
        for (long uid = from; uid < from + k; uid++) {
            insertNotification(uid, type);
        }
        // 再開待ちジョブ（cursor=先頭k件の末尾・status=PENDING）。
        NotificationFanoutJob job = saveWithMessages(newJob(TEST_SCOPE, scopeId, type, UUID.randomUUID(),
                "AC-2 再開", base + k, k));

        // done 条件: worker は cursor より後の受信者のみ配信し、合計 N・重複なしで job=DONE にする。
        // 現行は processReady 未実装ゆえ、この呼び出しが UnsupportedOperationException で FAIL=red。
        worker.processReady();

        long total = countNotifications(from, to, type);
        long distinct = countDistinctUsers(from, to, type);
        NotificationFanoutJob reloaded = jobRepository.findById(job.getId()).orElseThrow();
        log.info("[AC-2] total={} distinct={} status={}（N={}）",
                total, distinct, reloaded.getStatus(), RECIPIENTS_PER_SCOPE);

        assertThat(total).as("AC-2: 再開後の通知はちょうどN件（欠落なし）").isEqualTo(RECIPIENTS_PER_SCOPE);
        assertThat(distinct).as("AC-2: user_id は一意（cursor 以前の再生成による重複なし）")
                .isEqualTo(RECIPIENTS_PER_SCOPE);
        assertThat(reloaded.getStatus()).as("AC-2: 完了で DONE").isEqualTo(NotificationFanoutJobStatus.DONE);
    }

    // =====================================================================
    // AC-3 リトライ→DEAD_LETTER: 配信失敗で retry_count 増＋バックオフ、上限超で DEAD_LETTER（消えない）
    // ---------------------------------------------------------------------
    // FAILING_SCOPE_ID の受信者解決は例外を投げる（配信失敗シミュレーション）。worker.processOne を
    // 上限+1 回呼ぶ（各回 next_attempt_at をリセットして再取得可能にする）。
    // 現行: processOne 未実装ゆえ初回で FAIL(red)。
    // =====================================================================
    @Test
    @DisplayName("AC-3 配信失敗はリトライ→上限超で DEAD_LETTER（行は残る・worker 未実装=red）")
    void ac3_retriesThenDeadLetters() {
        String type = "FANOUT_IT_AC3";
        NotificationFanoutJob job = saveWithMessages(newJob(TEST_SCOPE, FAILING_SCOPE_ID, type,
                UUID.randomUUID(), "AC-3 失敗", 0L, 0));

        // done 条件: 失敗のたびに retry_count が増え、上限超で DEAD_LETTER に落ち、行は消えない。
        // 現行は processOne 未実装ゆえ、この呼び出しが UnsupportedOperationException で FAIL=red。
        for (int attempt = 0; attempt < 6; attempt++) {
            NotificationFanoutJob current = jobRepository.findById(job.getId()).orElseThrow();
            if (current.getStatus() == NotificationFanoutJobStatus.DEAD_LETTER) {
                break;
            }
            // バックオフで未来に置かれた next_attempt_at を現在に戻し、次の試行を可能にする（時間を進める代替）。
            current.setNextAttemptAt(LocalDateTime.now().minusSeconds(1));
            current.setStatus(NotificationFanoutJobStatus.PENDING);
            jobRepository.saveAndFlush(current);
            worker.processOne(jobRepository.findById(job.getId()).orElseThrow());
        }

        NotificationFanoutJob reloaded = jobRepository.findById(job.getId()).orElseThrow();
        log.info("[AC-3] status={} retryCount={}", reloaded.getStatus(), reloaded.getRetryCount());
        assertThat(reloaded.getStatus())
                .as("AC-3: リトライ上限超で DEAD_LETTER（行は消えず調査対象として残る）")
                .isEqualTo(NotificationFanoutJobStatus.DEAD_LETTER);
        assertThat(reloaded.getRetryCount()).as("AC-3: リトライが計上される").isGreaterThan(0);
    }

    // =====================================================================
    // AC-4 SKIP LOCKED: 別トランザクションが1ジョブを FOR UPDATE 保持中、findReady はそれを飛ばす
    // ---------------------------------------------------------------------
    // findReady は実装済み（native FOR UPDATE SKIP LOCKED）。infra 証明として現行 green で成立する。
    // 別スレッドが jobA を PESSIMISTIC_WRITE で保持中、本スレッドの findReady が jobA を返さず jobB を返す。
    // SKIP LOCKED が無ければ本スレッドはロック解放までブロックし、解放は assert 後のため timeout する
    // （＝ブロック＝SKIP LOCKED 未実装の検出）。
    // =====================================================================
    @Test
    @DisplayName("AC-4 findReady は FOR UPDATE 保持中のジョブを飛ばして別ジョブを返す（SKIP LOCKED・現行 green）")
    void ac4_findReadySkipsLockedJob() throws Exception {
        // 2 ジョブを最古（1970 起点）で作り、汚染ジョブより必ず先頭に並ぶようにする。
        NotificationFanoutJob jobA = saveWithMessages(newReadyJob("FANOUT_IT_AC4_A",
                LocalDateTime.of(1970, 1, 1, 0, 0)));
        NotificationFanoutJob jobB = saveWithMessages(newReadyJob("FANOUT_IT_AC4_B",
                LocalDateTime.of(1970, 1, 2, 0, 0)));
        UUID jobAId = jobA.getId();
        UUID jobBId = jobB.getId();

        CountDownLatch locked = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        ExecutorService holder = Executors.newSingleThreadExecutor();
        try {
            // Thread A: jobA を FOR UPDATE で保持し、release まで握り続ける。
            holder.submit(() -> {
                EntityManager em = emf.createEntityManager();
                try {
                    em.getTransaction().begin();
                    em.find(NotificationFanoutJob.class, jobAId, LockModeType.PESSIMISTIC_WRITE);
                    locked.countDown();
                    release.await(30, TimeUnit.SECONDS);
                    em.getTransaction().commit();
                } catch (Exception e) {
                    em.getTransaction().rollback();
                    throw new RuntimeException(e);
                } finally {
                    em.close();
                }
                return null;
            });

            assertThat(locked.await(30, TimeUnit.SECONDS)).as("jobA のロック取得を待つ").isTrue();

            // 本スレッド: findReady はロック中の jobA を飛ばし jobB を返す（tx 内で FOR UPDATE を効かせる）。
            TransactionTemplate tx = new TransactionTemplate(txManager);
            List<UUID> readyIds = tx.execute(status -> jobRepository
                    .findReady(LocalDateTime.now().plusYears(1), 50).stream()
                    .map(NotificationFanoutJob::getId).toList());

            log.info("[AC-4] findReady 返却={}（jobA={} をスキップ・jobB={} を含む想定）", readyIds, jobAId, jobBId);
            assertThat(readyIds).as("AC-4: ロック中の jobA はスキップされる（SKIP LOCKED）").doesNotContain(jobAId);
            assertThat(readyIds).as("AC-4: 未ロックの jobB は返る").contains(jobBId);
        } finally {
            release.countDown();
            holder.shutdown();
            holder.awaitTermination(30, TimeUnit.SECONDS);
        }
    }

    // =====================================================================
    // AC-7 enqueue O(1): enqueue は受信者数に依らず notification_fanout_jobs への INSERT 1文で返る
    // =====================================================================
    @Test
    @DisplayName("AC-7 enqueue はジョブ表への INSERT 1文で返る（受信者をページングしない・現行 no-op=red）")
    void ac7_enqueueIsSingleInsert() {
        statementCounter.register(JOB_INSERT, s -> s.contains("insert into notification_fanout_jobs"));
        statementCounter.reset();

        jobService.enqueue(TEST_SCOPE, String.valueOf(9_007L), "FANOUT_IT_AC7", UUID.randomUUID(), null,FanoutMessageKind.VILLAGE_EVENT_ADDED,
                    new String[]{"AC-7 O(1)"}, NotificationPriority.NORMAL, "FANOUT_IT", null, "/x", null);

        long inserts = statementCounter.count(JOB_INSERT);
        log.info("[AC-7] notification_fanout_jobs INSERT 文数 = {}（期待=1）", inserts);
        assertThat(inserts)
                .as("AC-7: enqueue は受信者数に依らずジョブ表への INSERT ちょうど1文。"
                        + "現行は enqueue が no-op ゆえ 0 文＝FAIL(red)")
                .isEqualTo(1L);
    }

    // =====================================================================
    // ヘルパ
    // =====================================================================

    private NotificationFanoutJob newJob(String scopeType, long scopeId, String type, UUID sourceEvent,
                                         String title, long cursor, long insertedCount) {
        LocalDateTime now = LocalDateTime.now();
        return NotificationFanoutJob.builder()
                .sourceEventUuid(sourceEvent)
                .scopeType(scopeType)
                .scopeRef(String.valueOf(scopeId))
                .notificationType(type)
                .priority(NotificationPriority.NORMAL)
                .sourceType("FANOUT_IT")
                .status(NotificationFanoutJobStatus.PENDING)
                .cursorSubjectId(cursor)
                .insertedCount(insertedCount)
                .retryCount(0)
                .nextAttemptAt(now.minusSeconds(1))
                .createdAt(now)
                .updatedAt(now)
                .build();
    }

    private NotificationFanoutJob newReadyJob(String type, LocalDateTime nextAttemptAt) {
        LocalDateTime now = LocalDateTime.now();
        return NotificationFanoutJob.builder()
                .sourceEventUuid(UUID.randomUUID())
                .scopeType(TEST_SCOPE)
                .scopeRef(String.valueOf(9_004L))
                .notificationType(type)
                .priority(NotificationPriority.NORMAL)
                .status(NotificationFanoutJobStatus.PENDING)
                .cursorSubjectId(0L)
                .insertedCount(0L)
                .retryCount(0)
                .nextAttemptAt(nextAttemptAt)
                .createdAt(now)
                .updatedAt(now)
                .build();
    }

    private void insertNotification(long userId, String type) {
        jdbc.update("INSERT INTO notifications "
                        + "(user_id, notification_type, priority, title, source_type, scope_type, is_read, created_at) "
                        + "VALUES (?, ?, 'NORMAL', ?, 'FANOUT_IT', 'SYSTEM', 0, ?)",
                userId, type, "pre-" + type, LocalDateTime.now());
    }

    private long countNotifications(long from, long to, String type) {
        Long c = jdbc.queryForObject(
                "SELECT COUNT(*) FROM notifications WHERE user_id BETWEEN ? AND ? AND notification_type = ?",
                Long.class, from, to, type);
        return c == null ? 0L : c;
    }

    private long countDistinctUsers(long from, long to, String type) {
        Long c = jdbc.queryForObject(
                "SELECT COUNT(DISTINCT user_id) FROM notifications WHERE user_id BETWEEN ? AND ? AND notification_type = ?",
                Long.class, from, to, type);
        return c == null ? 0L : c;
    }

    /**
     * 合成受信者ソース（村実装に依存しない）。scope_id に応じた連続 subject_id レンジをキーセットで返す。
     * {@link #FAILING_SCOPE_ID} だけは例外を投げ、AC-3 の配信失敗をシミュレートする。
     */
    @TestConfiguration
    static class TestRecipientSourceConfig {
        @Bean
        FanoutRecipientSource testRecipientSource() {
            return new FanoutRecipientSource() {
                @Override
                public String scopeType() {
                    return TEST_SCOPE;
                }

                @Override
                public List<FanoutRecipient> nextPage(FanoutPageRequest request) {
                    String scopeRef = request.scopeRef();
                    long cursorSubjectId = request.cursorSubjectId();
                    int limit = request.limit();
                    long scopeId = Long.parseLong(scopeRef);
                    if (scopeId == FAILING_SCOPE_ID) {
                        throw new IllegalStateException("AC-3 配信失敗シミュレーション（scopeRef=" + scopeRef + "）");
                    }
                    long base = recipientBase(scopeId);
                    // Issue #2871: 受信者は user_id ＋ locale の組。擬似ソースは既定ロケールで返す。
                    java.util.List<FanoutRecipient> page = new java.util.ArrayList<>();
                    for (int i = 1; i <= RECIPIENTS_PER_SCOPE && page.size() < limit; i++) {
                        long id = base + i;
                        if (id > cursorSubjectId) {
                            page.add(new FanoutRecipient(id, "ja"));
                        }
                    }
                    return page;
                }
            };
        }
    }

    /**
     * 計測用に実 {@link DataSource} を {@link CountingDataSource} でラップするテスト構成（AC-7）。
     * P1 RED IT と同じ BeanPostProcessor 方式。
     */
    @TestConfiguration
    static class CountingDsConfig {
        static final SqlStatementCounter COUNTER = new SqlStatementCounter();

        @Bean
        SqlStatementCounter sqlStatementCounter() {
            return COUNTER;
        }

        @Bean
        static BeanPostProcessor fanoutCountingDataSourceWrapper() {
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

    /**
     * Issue #2871: ワーカーはジョブのロケール別文面（子表）を読んでから配信する。
     *
     * <p>本番では enqueue が「親ジョブ 1 行＋文面 6 行」を同一トランザクションで確定するため、
     * 文面の無いジョブは存在しない。IT だけがジョブ行を直接組み立てているので、
     * ここで同じ前提（6 配信ロケールぶんの文面）を用意する。</p>
     */
    private com.mannschaft.app.notification.fanout.NotificationFanoutJob saveWithMessages(
            com.mannschaft.app.notification.fanout.NotificationFanoutJob job) {
        com.mannschaft.app.notification.fanout.NotificationFanoutJob saved = jobRepository.save(job);
        jobRepository.flush();
        java.util.List<com.mannschaft.app.notification.fanout.NotificationFanoutJobMessage> rows =
                new java.util.ArrayList<>();
        for (String tag : com.mannschaft.app.common.i18n.DeliveryLocales.TAGS) {
            rows.add(com.mannschaft.app.notification.fanout.NotificationFanoutJobMessage.builder()
                    .jobId(saved.getId())
                    .locale(tag)
                    .title("IT-title-" + tag)
                    .body("IT-body-" + tag)
                    .build());
        }
        fanoutJobMessageRepositoryForIt.saveAll(rows);
        return saved;
    }

    @org.springframework.beans.factory.annotation.Autowired
    private com.mannschaft.app.notification.fanout.NotificationFanoutJobMessageRepository
            fanoutJobMessageRepositoryForIt;
}
