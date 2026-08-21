package com.mannschaft.app.notification.fanout;

import com.mannschaft.app.notification.NotificationPriority;
import java.util.Map;
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
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

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
    /** ロケール別・描画済み文面の子表リポジトリ（Issue #2871）。 */
    private final NotificationFanoutJobMessageRepository jobMessageRepository;
    /** enqueue 時に 6 配信ロケールぶんの文面を描画するレンダラ（Issue #2871）。 */
    private final FanoutMessageRenderer messageRenderer;
    /** enqueue の INSERT を「呼び出し側 TX と隔離した独立 TX」で確定させるための REQUIRES_NEW テンプレート。 */
    private final TransactionTemplate enqueueTxTemplate;
    /** MeterRegistry（optional。narrowed test context 等では不在・P1 と同じ ObjectProvider 方式）。 */
    private final ObjectProvider<MeterRegistry> meterRegistryProvider;
    /** 自動シャード数算出のため scope_type から受信者ソース（{@code countRecipients}）を引くレジストリ（CMP-001⑤）。 */
    private final FanoutRecipientSourceRegistry recipientSourceRegistry;

    public NotificationFanoutJobService(NotificationFanoutJobRepository jobRepository,
                                        NotificationFanoutJobMessageRepository jobMessageRepository,
                                        FanoutMessageRenderer messageRenderer,
                                        PlatformTransactionManager transactionManager,
                                        ObjectProvider<MeterRegistry> meterRegistryProvider,
                                        FanoutRecipientSourceRegistry recipientSourceRegistry) {
        this.jobRepository = jobRepository;
        this.jobMessageRepository = jobMessageRepository;
        this.messageRenderer = messageRenderer;
        this.enqueueTxTemplate = new TransactionTemplate(transactionManager);
        this.enqueueTxTemplate.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        this.meterRegistryProvider = meterRegistryProvider;
        this.recipientSourceRegistry = recipientSourceRegistry;
    }

    /**
     * fan-out ジョブを 1 件 enqueue する（冪等・O(1)）。
     *
     * <p>受信者数に依らずジョブ表への INSERT ちょうど 1 文＋文面子表への一括 INSERT で返る。同一
     * {@code (scope_type, scope_ref, notification_type, source_event_uuid, shard_index)} の二重 enqueue は
     * {@code uk_fanout_idempotency} 違反となり、{@link DataIntegrityViolationException} を握って skip する
     * （これは「同一 fan-out の二重登録」＝正当な冪等のみを握る。他の例外は握らない）。</p>
     *
     * <h2>Issue #2871: 描画済み文字列ではなく「文面の種別＋型付き引数」を受ける</h2>
     * <p>従来の引数 {@code String title, String body} は呼び出し側が日本語で組み立てた完成品であり、
     * 受信者が後から展開される fan-out では受信者ごとの i18n が構造的に不可能だった。本メソッドは
     * {@link FanoutMessageKind}（テンプレート種別）と<b>利用者が書いた中身</b>（アンケート名・行事名 等）を
     * 受け取り、この場で 6 配信ロケールぶんの文面を描画して子表へ保存する。翻訳するのは properties 側の
     * 「枠」だけで、引数はそのまま差し込む（翻訳しない・改変しない）。</p>
     *
     * <p>切り詰め（title 200 / body 1000・コードポイント境界）も enqueue 時に確定するため、
     * リトライやデプロイをまたいでも同じ文面が再現される。</p>
     *
     * @param scopeType        受信者解決の戦略キー（{@link FanoutRecipientSource#scopeType()} と一致）
     * @param scopeRef         多型スコープ参照（村=UUID 文字列 / チーム・組織=ID 文字列）
     * @param notificationType 通知種別
     * @param sourceEventUuid  発生元イベント UUID（冪等キーの一部）
     * @param organizationId   テナント（NULL 可）
     * @param messageKind      文面テンプレート種別（村の 4 分岐もここで表す）
     * @param messageArgs      利用者が書いた中身（0〜2 個・すべて {@code String}）。翻訳せずそのまま差し込む
     * @param priority         優先度（NULL は NORMAL 相当）
     * @param sourceType       ソース種別（NULL 可）
     * @param sourceId         ソースID（NULL 可）
     * @param actionUrl        アクション URL（NULL 可）
     * @param actorId          実行者ID（NULL 可・システム発火は NULL）
     *
     * @implNote 本メソッド自体は非トランザクション。INSERT は {@code enqueueTxTemplate}（REQUIRES_NEW）で
     *           独立コミットし、ユニーク衝突は<b>トランザクション境界の外</b>で捕捉する。REQUIRES_NEW の内側で
     *           {@code catch} しても当該 TX は rollback-only のままコミット時に {@code UnexpectedRollbackException}
     *           を投げるため、隔離した TX を丸ごと外側で握るのが正しい。
     *           親ジョブと文面 6 行は<b>同一 TX</b>で確定する（文面の無いジョブを作らない）。
     */
    public void enqueue(String scopeType, String scopeRef, String notificationType, UUID sourceEventUuid,
                        Long organizationId, FanoutMessageKind messageKind, String[] messageArgs,
                        NotificationPriority priority,
                        String sourceType, Long sourceId, String actionUrl, Long actorId) {
        // 応援者トグルを指定しない既存経路（VILLAGE 還流 / TEAM シフト公開）は全員配信＝includeSupporters=true。
        enqueue(scopeType, scopeRef, notificationType, sourceEventUuid, organizationId, messageKind, messageArgs,
                priority, sourceType, sourceId, actionUrl, actorId, true);
    }

    /**
     * 応援者トグル {@code includeSupporters} を運搬する enqueue（Wave-2・ORG 耐久 fan-out）。
     *
     * <p>ジョブ列 {@code include_supporters} へ運搬し、ワーカーが受信者ソースへ
     * {@link FanoutPageRequest} 経由で渡す。冪等キー {@code uk_fanout_idempotency} には含めない
     * （トグル違いを別ジョブにしない設計）。冪等・O(1) は 12 引数版と同じ。</p>
     *
     * @param includeSupporters 応援者（純 SUPPORTER）を配信対象に含めるか（ORGANIZATION 以外は既定 true で挙動不変）
     */
    public void enqueue(String scopeType, String scopeRef, String notificationType, UUID sourceEventUuid,
                        Long organizationId, FanoutMessageKind messageKind, String[] messageArgs,
                        NotificationPriority priority,
                        String sourceType, Long sourceId, String actionUrl, Long actorId,
                        boolean includeSupporters) {
        // 文面の描画は TX の外で先に済ませる。キー欠落（恒久的な設定不備）は握り潰さず例外として伝播させ、
        // 「ジョブ行だけ作られて文面が無い」中途半端な状態を作らない（AC-6）。
        Map<String, FanoutMessageRenderer.RenderedMessage> messages =
                messageRenderer.renderAllLocales(messageKind, messageArgs == null ? new String[0] : messageArgs);

        LocalDateTime now = LocalDateTime.now();
        // 真の O(1)（AC-7）: 受信者数を数えず、母集団評価は worker 側へ先送りする（B案・マスター裁可 2026-08-08）。
        // enqueue は常に「親ジョブ 1 行＋文面 6 行」だけを INSERT する。shard_index=0・shard_count=0
        //（0＝「シャード未評価」の番人値）で作り、初回 claim した worker が resolveAndSplitShards で
        // N を確定して子シャード行を発行する。
        NotificationFanoutJob job = NotificationFanoutJob.builder()
                .sourceEventUuid(sourceEventUuid)
                .scopeType(scopeType)
                .scopeRef(scopeRef)
                .notificationType(notificationType)
                .organizationId(organizationId)
                .priority(priority == null ? NotificationPriority.NORMAL : priority)
                .sourceType(sourceType)
                .sourceId(sourceId)
                .actionUrl(actionUrl)
                .actorId(actorId)
                .includeSupporters(includeSupporters)
                .shardIndex((short) 0)
                .shardCount((short) 0) // 0＝未評価（worker が初回 claim 時に確定）
                .status(NotificationFanoutJobStatus.PENDING)
                .cursorSubjectId(0L)
                .insertedCount(0L)
                .retryCount(0)
                .nextAttemptAt(now)
                .createdAt(now)
                .updatedAt(now)
                .build();
        try {
            // 独立 TX（REQUIRES_NEW）で親ジョブ 1 行＋文面 6 行を INSERT・確定する。ユニーク違反時はこの TX のみ
            // 丸ごとロールバック。例外は TX 境界の外（本 try）で捕捉するため呼び出し側 TX は無傷。
            enqueueTxTemplate.executeWithoutResult(status -> {
                jobRepository.save(job);
                jobRepository.flush();
                jobMessageRepository.saveAll(buildMessages(job.getId(), messages));
                jobMessageRepository.flush();
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

    /** 描画済み文面 Map をジョブ配下の子エンティティ群へ写す（配信ロケール数ぶん＝6 行）。 */
    private static List<NotificationFanoutJobMessage> buildMessages(
            UUID jobId, Map<String, FanoutMessageRenderer.RenderedMessage> messages) {
        List<NotificationFanoutJobMessage> rows = new ArrayList<>(messages.size());
        for (Map.Entry<String, FanoutMessageRenderer.RenderedMessage> entry : messages.entrySet()) {
            rows.add(NotificationFanoutJobMessage.builder()
                    .jobId(jobId)
                    .locale(entry.getKey())
                    .title(entry.getValue().title())
                    .body(entry.getValue().body())
                    .build());
        }
        return rows;
    }

    /**
     * 母集団しきい値ポリシーで自動シャード数を算出する（CMP-001⑤）。
     *
     * <p>{@code count > SHARD_THRESHOLD} なら {@code min(MAX_SHARDS, ceil(count / SHARD_TARGET_SIZE))} を返す。
     * 閾値以下・カウント非対応（{@code -1}）・母集団0はすべて {@code 1} を返し従来の単一行経路を保つ。</p>
     */
    static int computeShardCount(long recipientCount) {
        if (recipientCount <= SHARD_THRESHOLD) {
            // 閾値以下（カウント非対応の -1・母集団0・小規模）は単一シャード（既存挙動）。
            return 1;
        }
        long shards = (recipientCount + SHARD_TARGET_SIZE - 1) / SHARD_TARGET_SIZE; // ceil
        return (int) Math.min(MAX_SHARDS, Math.max(1L, shards));
    }

    /**
     * worker が初回 claim 時にシャードを確定・分割する（B案・CMP-001⑤ 是正・マスター裁可 2026-08-08）。
     *
     * <p>enqueue は O(1) のため親ジョブ 1 行（{@code shard_index=0}・{@code shard_count=0}）しか作らない。
     * 本メソッドは初回 claim した worker から呼ばれ、母集団を数えてシャード数 N を確定し、子シャード行
     * （{@code shard_index=1..N-1}・{@code shard_count=N}）を発行したうえで親自身の {@code shard_count} を N に更新する。
     * 子 INSERT と親更新は<b>同一 REQUIRES_NEW TX</b>で原子確定する（途中クラッシュで中途半端に残さない）。</p>
     *
     * <h2>冪等・クラッシュ耐性の不変条件</h2>
     * <p>{@code shard_count != 0}（評価済 or レガシー行）なら何もせず現状の N を返す（冪等）。分割コミット前に
     * クラッシュした場合、親は {@code shard_count=0} のまま残り（子 INSERT も同一 TX でロールバック済み）、
     * {@link NotificationFanoutStuckRecoveryBatch} が RUNNING を PENDING へ戻す→再 claim→再度本メソッドが走る。
     * その際、既存の子シャード行は事前 existence チェックで除外し、万一の競合は {@code uk_fanout_idempotency}
     * （{@code shard_index} 込み）が二重挿入を弾く（別原因の整合性違反は握り潰さず露見させる）。</p>
     *
     * @param jobId 親ジョブ（{@code shard_index=0}）の ID
     * @return 確定したシャード総数 N（{@code >= 1}）
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public int resolveAndSplitShards(UUID jobId) {
        NotificationFanoutJob parent = jobRepository.findById(jobId).orElseThrow();
        short existing = parent.getShardCount();
        if (existing != 0) {
            // 既に評価済み（or レガシー行 shard_count>=1）。冪等に現状を返す（再 claim・再入で二重分割しない）。
            return existing;
        }
        // 母集団を数えてシャード数 N を確定する（カウント非対応・未登録 scope_type は N=1）。
        FanoutRecipientSource source = recipientSourceRegistry.resolve(parent.getScopeType()).orElse(null);
        boolean includeSupporters = !Boolean.FALSE.equals(parent.getIncludeSupporters());
        long recipientCount = source == null ? -1L : source.countRecipients(parent.getScopeRef(), includeSupporters);
        int shardCount = computeShardCount(recipientCount);

        // 既存の子シャード行を洗い出す（クラッシュ再開時の二重挿入防止・冪等）。
        List<NotificationFanoutJob> siblings = jobRepository
                .findByScopeTypeAndScopeRefAndNotificationTypeAndSourceEventUuidOrderByShardIndexAsc(
                        parent.getScopeType(), parent.getScopeRef(),
                        parent.getNotificationType(), parent.getSourceEventUuid());
        Set<Short> existingIndices = siblings.stream()
                .map(NotificationFanoutJob::getShardIndex)
                .collect(Collectors.toSet());

        LocalDateTime now = LocalDateTime.now();
        List<NotificationFanoutJob> children = new ArrayList<>();
        for (short shardIndex = 1; shardIndex < shardCount; shardIndex++) {
            if (existingIndices.contains(shardIndex)) {
                continue; // 再開時に既存＝二重挿入しない（冪等 skip）。
            }
            children.add(NotificationFanoutJob.builder()
                    .sourceEventUuid(parent.getSourceEventUuid())
                    .scopeType(parent.getScopeType())
                    .scopeRef(parent.getScopeRef())
                    .notificationType(parent.getNotificationType())
                    .organizationId(parent.getOrganizationId())
                    .priority(parent.getPriority())
                    .sourceType(parent.getSourceType())
                    .sourceId(parent.getSourceId())
                    .actionUrl(parent.getActionUrl())
                    .actorId(parent.getActorId())
                    .includeSupporters(parent.getIncludeSupporters())
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
        if (!children.isEmpty()) {
            // 子 INSERT。同一キー・同一 shard_index の二重挿入は uk_fanout_idempotency が弾く
            // （別原因の整合性違反はここで rethrow され、TX ロールバック→recovery で再走）。
            jobRepository.saveAll(children);
            jobRepository.flush();
            // Issue #2871: 文面は親と同一（同じイベントの同じ文言）。子シャードにも同じ 6 行を複製する。
            // 親から読んだ「その時点の描画結果」をそのまま写すため、分割の前後で文面が食い違わない
            // （分割中に翻訳がデプロイされても 1 イベント内の文面は一貫する）。
            List<NotificationFanoutJobMessage> parentMessages = jobMessageRepository.findByJobId(parent.getId());
            List<NotificationFanoutJobMessage> childMessages = new ArrayList<>();
            for (NotificationFanoutJob child : children) {
                for (NotificationFanoutJobMessage message : parentMessages) {
                    childMessages.add(NotificationFanoutJobMessage.builder()
                            .jobId(child.getId())
                            .locale(message.getLocale())
                            .title(message.getTitle())
                            .body(message.getBody())
                            .build());
                }
            }
            if (!childMessages.isEmpty()) {
                jobMessageRepository.saveAll(childMessages);
            }
        }

        // 親自身（shard_index=0）を shard_count=N に更新（子 INSERT と同一 TX で原子確定）。
        parent.setShardCount((short) shardCount);
        parent.setUpdatedAt(now);
        jobRepository.save(parent);
        jobRepository.flush();
        return shardCount;
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
        recordFailure(jobId, error, maxRetry, false);
    }

    /**
     * 配信失敗を記録する（永久失敗の即 DEAD_LETTER 短絡に対応した版・CMP-030）。
     *
     * <p>{@code immediateDeadLetter=false} は従来どおり「上限未満は指数バックオフで {@code FAILED}、
     * 上限到達で {@code DEAD_LETTER}」。{@code immediateDeadLetter=true} は「リトライしても永遠に成功しない」
     * と判っている恒久失敗（例: 未登録 {@code scope_type}＝設定不備）向けで、{@code retry_count} を 1 増やしたうえで
     * 即 {@code DEAD_LETTER} に落とす。無駄なリトライ待ち（バックオフ×上限回）を挟まず、かつ RUNNING 残置
     * （＝stuck リカバリによる無限ループ）を断ち切る。行は消さず {@code last_error} に理由を残す（AC-D）。</p>
     *
     * @param immediateDeadLetter リトライ不能な恒久失敗として即 DEAD_LETTER に落とすか
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void recordFailure(UUID jobId, String error, int maxRetry, boolean immediateDeadLetter) {
        NotificationFanoutJob job = jobRepository.findById(jobId).orElseThrow();
        LocalDateTime now = LocalDateTime.now();
        int rc = job.getRetryCount() + 1;
        job.setRetryCount(rc);
        job.setLastError(truncate(error));
        job.setUpdatedAt(now);
        boolean deadLettered;
        if (immediateDeadLetter || rc >= maxRetry) {
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
