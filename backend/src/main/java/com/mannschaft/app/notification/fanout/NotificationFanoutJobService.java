package com.mannschaft.app.notification.fanout;

import com.mannschaft.app.notification.NotificationPriority;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * 通知 fan-out 耐久ジョブの enqueue 口＋ジョブ状態遷移サービス（P2）。
 *
 * <p>村行事作成などの「入口」は受信者を一切展開せず、本サービスで {@link NotificationFanoutJob} を
 * <b>1 行だけ</b> INSERT する（O(1)・AC-7）。実配信は裏ワーカー {@link NotificationFanoutWorker} が担う。
 * 同一 fan-out の二重 enqueue は DB のユニーク制約 {@code uk_fanout_idempotency} に依り、衝突を握って
 * skip する冪等契約とする（AC-1）。</p>
 *
 * <h2>トランザクション境界（クラッシュ再開の要）</h2>
 * <p>enqueue／カーソル前進／状態遷移は<b>それぞれ独立コミット</b>（{@code REQUIRES_NEW}）とする。
 * ワーカーがチャンクを配信するたびに {@code cursor_subject_id} を独立コミットで前進させることで、
 * プロセスクラッシュ後の再開が「処理済みカーソルの直後」から始まり、欠落なく続行できる（AC-2）。
 * enqueue の冪等衝突ロールバックが呼び出し側（還流の system 投稿トランザクション等）を巻き込まないよう、
 * enqueue も {@code REQUIRES_NEW} で隔離する。</p>
 */
@Slf4j
@Service
public class NotificationFanoutJobService {

    private static final int LAST_ERROR_MAX = 500;

    /**
     * 自動シャード化のしきい値ポリシー（CMP-001⑤・マスター裁可）。
     * 受信者数が {@link #SHARD_THRESHOLD} を超えたら自動シャード化し、
     * {@code shard_count = min(MAX_SHARDS, ceil(recipientCount / SHARD_TARGET_SIZE))} 本のジョブ行を発行する。
     * しきい値以下（カウント非対応の {@code -1} を含む）は従来どおり {@code shard_count=1}（単一行）。
     */
    static final long SHARD_THRESHOLD = 10_000L;
    /** 1 シャードが担う目安の受信者数（総数をこれで割り上げてシャード数を決める）。 */
    static final long SHARD_TARGET_SIZE = 20_000L;
    /** シャード数の上限（並列度の天井・ジョブ行の過剰生成防止）。 */
    static final int MAX_SHARDS = 32;

    /** リトライバックオフの基準秒（指数：base * 2^(retryCount-1)、上限つき）。 */
    private static final long BACKOFF_BASE_SECONDS = 30L;
    private static final long BACKOFF_MAX_SECONDS = 3_600L;

    /** DEAD_LETTER 遷移カウンタ（silent drop 根絶の可観測性・P1 命名に整合・AC-10）。 */
    static final String METRIC_DEAD_LETTER = "mannschaft.notification.fanout.job.dead_letter";
    /** リトライ加算カウンタ（AC-10）。 */
    static final String METRIC_RETRY = "mannschaft.notification.fanout.job.retry";

    private final NotificationFanoutJobRepository jobRepository;
    /** enqueue の INSERT を「呼び出し側 TX と隔離した独立 TX」で確定させるための REQUIRES_NEW テンプレート。 */
    private final TransactionTemplate enqueueTxTemplate;
    /** MeterRegistry（optional。narrowed test context 等では不在・P1 と同じ ObjectProvider 方式）。 */
    private final ObjectProvider<MeterRegistry> meterRegistryProvider;
    /** 自動シャード数算出のため scope_type から受信者ソース（{@code countRecipients}）を引くレジストリ（CMP-001⑤）。 */
    private final FanoutRecipientSourceRegistry recipientSourceRegistry;

    public NotificationFanoutJobService(NotificationFanoutJobRepository jobRepository,
                                        PlatformTransactionManager transactionManager,
                                        ObjectProvider<MeterRegistry> meterRegistryProvider,
                                        FanoutRecipientSourceRegistry recipientSourceRegistry) {
        this.jobRepository = jobRepository;
        this.enqueueTxTemplate = new TransactionTemplate(transactionManager);
        this.enqueueTxTemplate.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        this.meterRegistryProvider = meterRegistryProvider;
        this.recipientSourceRegistry = recipientSourceRegistry;
    }

    /**
     * fan-out ジョブを 1 件 enqueue する（冪等・O(1)）。
     *
     * <p>受信者数に依らずジョブ表への INSERT ちょうど 1 文で返る。同一
     * {@code (scope_type, scope_ref, notification_type, source_event_uuid)} の二重 enqueue は
     * {@code uk_fanout_idempotency} 違反となり、{@link DataIntegrityViolationException} を握って skip する
     * （これは「同一 fan-out の二重登録」＝正当な冪等のみを握る。他の例外は握らない）。</p>
     *
     * @param scopeType        受信者解決の戦略キー（{@link FanoutRecipientSource#scopeType()} と一致）
     * @param scopeRef         多型スコープ参照（村=UUID 文字列 / チーム・組織=ID 文字列）
     * @param notificationType 通知種別
     * @param sourceEventUuid  発生元イベント UUID（冪等キーの一部）
     * @param organizationId   テナント（NULL 可）
     * @param title            通知タイトル
     * @param body             通知本文（NULL 可）
     * @param priority         優先度（NULL は NORMAL 相当）
     * @param sourceType       ソース種別（NULL 可）
     * @param sourceId         ソースID（NULL 可）
     * @param actionUrl        アクション URL（NULL 可）
     * @param actorId          実行者ID（NULL 可・システム発火は NULL）
     *
     * @implNote 本メソッド自体は非トランザクション。INSERT は {@code enqueueTxTemplate}（REQUIRES_NEW）で
     *           独立コミットし、ユニーク衝突は<b>トランザクション境界の外</b>で捕捉する。REQUIRES_NEW の内側で
     *           {@code catch} しても当該 TX は rollback-only のままコミット時に {@code UnexpectedRollbackException}
     *           を投げるため、隔離した TX を丸ごと外側で握るのが正しい（呼び出し側＝還流の system 投稿 TX を巻き込まない）。
     */
    public void enqueue(String scopeType, String scopeRef, String notificationType, UUID sourceEventUuid,
                        Long organizationId, String title, String body, NotificationPriority priority,
                        String sourceType, Long sourceId, String actionUrl, Long actorId) {
        // 応援者トグルを指定しない既存経路（VILLAGE 還流 / TEAM シフト公開）は全員配信＝includeSupporters=true。
        enqueue(scopeType, scopeRef, notificationType, sourceEventUuid, organizationId, title, body, priority,
                sourceType, sourceId, actionUrl, actorId, true);
    }

    /**
     * 応援者トグル {@code includeSupporters} を運搬する enqueue（Wave-2・ORG 耐久 fan-out）。
     *
     * <p>ジョブ列 {@code include_supporters} へ運搬し、ワーカーが受信者ソースの 4 引数版
     * {@link FanoutRecipientSource#nextPage(String, long, int, boolean)} へ渡す。冪等キー
     * {@code uk_fanout_idempotency} には含めない（トグル違いを別ジョブにしない設計）。冪等・O(1) は 12 引数版と同じ。</p>
     *
     * @param includeSupporters 応援者（純 SUPPORTER）を配信対象に含めるか（ORGANIZATION 以外は既定 true で挙動不変）
     */
    public void enqueue(String scopeType, String scopeRef, String notificationType, UUID sourceEventUuid,
                        Long organizationId, String title, String body, NotificationPriority priority,
                        String sourceType, Long sourceId, String actionUrl, Long actorId,
                        boolean includeSupporters) {
        LocalDateTime now = LocalDateTime.now();
        // 母集団しきい値で自動シャード数を算出（閾値以下／カウント非対応は 1・従来どおり単一行）。
        int shardCount = resolveShardCount(scopeType, scopeRef, includeSupporters);
        // shardCount 本のジョブ行（shard_index=0..N-1・shard_count=N）を組み立てる。
        List<NotificationFanoutJob> jobs = new ArrayList<>(shardCount);
        for (short shardIndex = 0; shardIndex < shardCount; shardIndex++) {
            jobs.add(NotificationFanoutJob.builder()
                    .sourceEventUuid(sourceEventUuid)
                    .scopeType(scopeType)
                    .scopeRef(scopeRef)
                    .notificationType(notificationType)
                    .organizationId(organizationId)
                    .title(title)
                    .body(body)
                    .priority(priority == null ? NotificationPriority.NORMAL : priority)
                    .sourceType(sourceType)
                    .sourceId(sourceId)
                    .actionUrl(actionUrl)
                    .actorId(actorId)
                    .includeSupporters(includeSupporters)
                    .shardIndex(shardIndex)
                    .shardCount((short) shardCount)
                    .status(NotificationFanoutJobStatus.PENDING)
                    .cursorSubjectId(0L)
                    .insertedCount(0L)
                    .retryCount(0)
                    .nextAttemptAt(now)
                    .createdAt(now)
                    .updatedAt(now)
                    .build());
        }
        try {
            // 独立 TX（REQUIRES_NEW）で全シャード行を一括 INSERT・原子確定する。ユニーク違反時はこの TX のみ
            // 丸ごとロールバック（部分的なシャード行が中途半端に残らない）。例外は TX 境界の外（本 try）で捕捉するため
            // 呼び出し側 TX は無傷。saveAll 後に flush して衝突を execute の内側で発火させる（TX 境界内でロールバック）。
            enqueueTxTemplate.executeWithoutResult(status -> {
                jobRepository.saveAll(jobs);
                jobRepository.flush();
            });
        } catch (DataIntegrityViolationException e) {
            // 握るのは「同一 fan-out の二重登録（uk_fanout_idempotency 衝突）」だけ。
            // catch を DataIntegrityViolationException で広く受けると、NOT NULL 違反や別制約違反まで
            // 「冪等 skip」として無言で握り潰し、通知が痕跡なく消える（握り潰し禁止に抵触）。
            // そこで当該冪等キーのジョブが実在する時のみ skip とし、実在しない＝別原因なら rethrow する。
            boolean idempotentDuplicate = jobRepository
                    .findByScopeTypeAndScopeRefAndNotificationTypeAndSourceEventUuid(
                            scopeType, scopeRef, notificationType, sourceEventUuid)
                    .isPresent();
            if (!idempotentDuplicate) {
                // 冪等衝突ではない整合性違反。握らず露見させる（呼び出し側の best-effort catch で可視化される）。
                throw e;
            }
            log.debug("fan-out ジョブは既に登録済み（冪等 skip）: scopeType={} scopeRef={} type={} sourceEvent={}",
                    scopeType, scopeRef, notificationType, sourceEventUuid);
        }
    }

    /**
     * 母集団しきい値ポリシーで自動シャード数を算出する（CMP-001⑤）。
     *
     * <p>受信者ソースが {@link FanoutRecipientSource#countRecipients} を実装している（ORGANIZATION）場合のみ
     * 総数を取り、{@code count > SHARD_THRESHOLD} なら
     * {@code min(MAX_SHARDS, ceil(count / SHARD_TARGET_SIZE))} を返す。閾値以下・カウント非対応（{@code -1}）・
     * 未登録 scope_type・分割対象外（VILLAGE / TEAM）はすべて {@code 1} を返し従来の単一行経路を保つ。</p>
     */
    private int resolveShardCount(String scopeType, String scopeRef, boolean includeSupporters) {
        FanoutRecipientSource source = recipientSourceRegistry.resolve(scopeType).orElse(null);
        if (source == null) {
            return 1;
        }
        long recipientCount = source.countRecipients(scopeRef, includeSupporters);
        if (recipientCount <= SHARD_THRESHOLD) {
            // 閾値以下（カウント非対応の -1・母集団0・小規模）は単一シャード（既存挙動）。
            return 1;
        }
        long shards = (recipientCount + SHARD_TARGET_SIZE - 1) / SHARD_TARGET_SIZE; // ceil
        return (int) Math.min(MAX_SHARDS, Math.max(1L, shards));
    }

    /**
     * 実行可能な PENDING ジョブを {@code FOR UPDATE SKIP LOCKED} で取得し、同一 TX 内で {@code RUNNING} に
     * 遷移させて返す（他 pod／並行ワーカーとの二重取得を構造的に防ぐ・AC-4）。返却されたジョブは
     * {@link NotificationFanoutWorker#processOne} が排出する。RUNNING のまま残った残骸は
     * {@link NotificationFanoutStuckRecoveryBatch} が回収する。
     */
    @Transactional
    public List<NotificationFanoutJob> claimReady(LocalDateTime now, int limit) {
        List<NotificationFanoutJob> jobs = jobRepository.findReady(now, limit);
        LocalDateTime ts = LocalDateTime.now();
        for (NotificationFanoutJob job : jobs) {
            job.setStatus(NotificationFanoutJobStatus.RUNNING);
            job.setUpdatedAt(ts);
        }
        // findReady は managed entity を返すため dirty checking で RUNNING がコミット時にフラッシュされる。
        return jobs;
    }

    /** ジョブを RUNNING に遷移させる（{@link #processOne} を直接呼ぶ経路の保険・独立コミット）。 */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void markRunning(UUID jobId) {
        NotificationFanoutJob job = jobRepository.findById(jobId).orElseThrow();
        job.setStatus(NotificationFanoutJobStatus.RUNNING);
        job.setUpdatedAt(LocalDateTime.now());
        jobRepository.save(job);
    }

    /**
     * チャンク配信 1 回ぶんのカーソルを前進させ独立コミットする（クラッシュ再開の要・AC-2）。
     * 直前の {@link NotificationBulkFanoutService#insertAndDispatchChunk} で通知行が確定した直後に呼び、
     * 「処理済みカーソル」を耐久化する。再開はこのカーソルの直後から始まる。
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void advanceCursor(UUID jobId, long newCursor, long addedCount) {
        NotificationFanoutJob job = jobRepository.findById(jobId).orElseThrow();
        job.setCursorSubjectId(newCursor);
        job.setInsertedCount(job.getInsertedCount() + addedCount);
        job.setUpdatedAt(LocalDateTime.now());
        jobRepository.save(job);
    }

    /** 全受信者の配信完了で {@code DONE} に遷移させる（独立コミット）。 */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void markDone(UUID jobId) {
        NotificationFanoutJob job = jobRepository.findById(jobId).orElseThrow();
        job.setStatus(NotificationFanoutJobStatus.DONE);
        job.setUpdatedAt(LocalDateTime.now());
        jobRepository.save(job);
    }

    /**
     * 配信失敗を記録する。{@code retry_count} を増やし、上限未満なら指数バックオフで {@code FAILED}（再試行待ち）、
     * 上限到達で {@code DEAD_LETTER}（行は消さず調査・手動再投入対象として残す・AC-3）に遷移させる。
     * 呼び出し元の例外で巻き戻らないよう独立コミット（{@code REQUIRES_NEW}）で確定する。
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void recordFailure(UUID jobId, String error, int maxRetry) {
        NotificationFanoutJob job = jobRepository.findById(jobId).orElseThrow();
        LocalDateTime now = LocalDateTime.now();
        int rc = job.getRetryCount() + 1;
        job.setRetryCount(rc);
        job.setLastError(truncate(error));
        job.setUpdatedAt(now);
        boolean deadLettered;
        if (rc >= maxRetry) {
            job.setStatus(NotificationFanoutJobStatus.DEAD_LETTER);
            deadLettered = true;
        } else {
            job.setStatus(NotificationFanoutJobStatus.FAILED);
            job.setNextAttemptAt(now.plusSeconds(backoffSeconds(rc)));
            deadLettered = false;
        }
        jobRepository.save(job);

        // 可観測性（AC-10・silent drop 根絶）: リトライ加算は毎回、DEAD_LETTER 転落は遷移時のみ計上。
        incrementCounter(METRIC_RETRY);
        if (deadLettered) {
            incrementCounter(METRIC_DEAD_LETTER);
        }
    }

    /** カウンタを null 安全に +1（レジストリ不在の narrowed test context では何もしない・P1 と同方式）。 */
    private void incrementCounter(String name) {
        if (meterRegistryProvider == null) {
            return;
        }
        MeterRegistry registry = meterRegistryProvider.getIfAvailable();
        if (registry == null) {
            return;
        }
        registry.counter(name).increment();
    }

    private static long backoffSeconds(int retryCount) {
        long shift = Math.min(retryCount - 1, 20); // オーバーフロー防止
        long seconds = BACKOFF_BASE_SECONDS << shift;
        return Math.min(seconds, BACKOFF_MAX_SECONDS);
    }

    private static String truncate(String s) {
        if (s == null) {
            return null;
        }
        return s.length() <= LAST_ERROR_MAX ? s : s.substring(0, LAST_ERROR_MAX);
    }
}
