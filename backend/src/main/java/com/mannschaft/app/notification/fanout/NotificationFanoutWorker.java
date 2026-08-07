package com.mannschaft.app.notification.fanout;

import com.mannschaft.app.common.batch.BatchEndpointExempt;
import com.mannschaft.app.notification.NotificationScopeType;
import com.mannschaft.app.notification.service.NotificationBulkFanoutService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import net.javacrumbs.shedlock.core.LockAssert;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

/**
 * 通知 fan-out 耐久ジョブの裏ワーカー（P2）。
 *
 * <p>一定間隔で実行可能ジョブを {@link NotificationFanoutJobService#claimReady} で取得し
 *（{@code FOR UPDATE SKIP LOCKED} で claim → RUNNING）、各ジョブを {@code cursor_subject_id} から再開して
 * 受信者をチャンク配信する。ShedLock により複数 pod でも同時実行されない（email_outbox ワーカー前例）。</p>
 *
 * <h2>クラッシュ再開の要（AC-2）</h2>
 * <p>{@link #processOne} は 1 チャンク配信するたびに
 * {@link NotificationFanoutJobService#advanceCursor} でカーソルを<b>独立コミット</b>する。
 * P1 {@link NotificationBulkFanoutService#insertAndDispatchChunk} が通知行をチャンク単位で確定した直後に
 * カーソルを耐久化することで、プロセスがクラッシュしても「処理済みカーソルの直後」から再開でき、
 * 欠落なく完走する。空ページで {@code DONE}、配信失敗はリトライ（バックオフ）／上限超で {@code DEAD_LETTER}。</p>
 *
 * <h2>テストからの直接呼び出し</h2>
 * <p>test プロファイルは {@code @EnableScheduling} 無効ゆえ {@link #poll()} は自動発火しない。テストは
 * {@link #processReady()} / {@link #processOne(NotificationFanoutJob)} を直接呼ぶ（{@code LockAssert} を経由しない）。</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class NotificationFanoutWorker {

    /**
     * 1 周回で取得するジョブ数の上限。
     *
     * <p>{@link NotificationFanoutJobService#MAX_SHARDS}（=32）と揃える（検分ステップ2の是正・CMP-001⑤）。
     * 1 スコープの enqueue は最大 32 本のシャードジョブに分割されるが、{@code BATCH_SIZE} が
     * {@code MAX_SHARDS} を下回ると同一スコープのシャード群が 1 回の {@link #processReady} で
     * 全数 claim できず 2 波以降に直列化し、500,000 人（25 シャード）規模で ≤120 秒 SLO を割り込む。
     * {@code MAX_SHARDS} と同値にすることで 1 スコープの全シャードを同一 poll で並列消化できる。</p>
     */
    static final int BATCH_SIZE = 32;

    /** 1 チャンクで配信する受信者数（P1 の受信者ストリームチャンクと同規模）。 */
    static final int CHUNK_SIZE = 500;

    /** リトライ上限。到達で DEAD_LETTER（AC-3）。 */
    static final int MAX_RETRY = 5;

    private final FanoutRecipientSourceRegistry recipientSourceRegistry;
    private final NotificationFanoutJobService jobService;
    private final NotificationBulkFanoutService bulkFanoutService;

    /**
     * 5 秒間隔で実行可能ジョブを 1 周回処理する。
     *
     * <p>{@code poll} 全体を 1 トランザクションで包むと per-job／per-chunk の独立コミット（再開・リトライの確定）が
     * 巻き戻るため、外側 TX を張らない契約を {@code NEVER} で固定する（email_outbox ワーカー前例）。</p>
     *
     * <p><b>バッチ実行履歴基盤（{@code @BatchEndpoint}）へ登録しない理由</b>:
     * 5 秒間隔＝日次 17,280 回の起動であり、1 回ごとに実行履歴を書くと
     * 履歴テーブルが「実行可能ジョブ 0 件」の記録で埋まり、日次・月次バッチの記録が埋没する。
     * fan-out の進捗・失敗は {@code notification_fanout_jobs} のジョブ行そのもの
     * （ステータス・{@code cursor_subject_id}・リトライ回数）が記録しているため、実行履歴は不要である。</p>
     */
    @BatchEndpointExempt("5 秒間隔（日次 17,280 回）の高頻度ワーカーであり、"
        + "実行履歴を書くと日次・月次バッチの記録が埋没する。進捗は notification_fanout_jobs のジョブ行が記録")
    @Scheduled(fixedDelay = 5_000)
    @SchedulerLock(name = "notificationFanoutWorker", lockAtMostFor = "PT5M", lockAtLeastFor = "PT5S")
    @Transactional(propagation = Propagation.NEVER)
    public void poll() {
        LockAssert.assertLocked();
        processReady();
    }

    /**
     * 実行可能ジョブを claim（RUNNING 化）して各ジョブを {@link #processOne} で
     * <b>Virtual Threads で並列</b>に排出する（1 周回・CMP-001⑤）。
     *
     * <p><b>並列化の狙い（AC-1/6）</b>: 1 スコープが複数シャードジョブに分割されている場合、
     * 各シャードを別スレッドで同時配信してスループットを稼ぐ。{@code @SchedulerLock}（クラスタ単一 poll）は
     * {@link #poll()} 側で維持しており、並列度は 1 poll 内に閉じる（複数 pod が同一 batch を掴まない）。</p>
     *
     * <p><b>claim 整合</b>: {@link NotificationFanoutJobService#claimReady} が
     * {@code FOR UPDATE SKIP LOCKED} ＋ RUNNING 化で排他 claim 済みの batch を並列処理するだけであり、
     * 同一ジョブを 2 スレッドが持つことはない（並列化で新たな二重 claim は生じない）。</p>
     *
     * <p><b>例外分離（AC-6）</b>: 1 ジョブ（=1 シャード）の {@link #processOne} が例外を投げても
     * 他ジョブの処理は止めない。各タスクの {@code Future#get} で throw を捕捉してログに残す
     * （握り潰さずログと状態遷移で露見させる）。配信失敗は {@code processOne} 内で {@code recordFailure} 済み。</p>
     *
     * <p><b>コネクション有界性</b>: 並列度の上限は batch 件数（{@code BATCH_SIZE}=32）であり、
     * Hikari 最大プール（50）に収まる。Virtual Thread 自体は多重化されるが、同時に DB を掴むタスク数は
     * batch 件数が上限のため枯渇しない想定（最終的な 50 万実測は検分で裏取り）。</p>
     */
    public void processReady() {
        List<NotificationFanoutJob> batch = jobService.claimReady(LocalDateTime.now(), BATCH_SIZE);
        if (batch.isEmpty()) {
            return;
        }
        log.debug("NotificationFanoutWorker claimed {} jobs", batch.size());
        try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
            List<Future<?>> futures = batch.stream()
                    .<Future<?>>map(job -> executor.submit(() -> processOne(job)))
                    .toList();
            for (int i = 0; i < futures.size(); i++) {
                NotificationFanoutJob job = batch.get(i);
                try {
                    futures.get(i).get();
                } catch (Exception ex) {
                    // processOne は失敗を内部で recordFailure に落とす設計。ここに来るのは想定外（バグ）。
                    // 1 ジョブの想定外例外で他ジョブを止めないため、ここで捕捉してログに残す（露見させる）。
                    log.error("NotificationFanoutWorker: 想定外の例外 jobId={}", job.getId(), ex);
                }
            }
        }
    }

    /**
     * ジョブ 1 件を {@code cursor_subject_id} から再開し、レジストリで解決した受信者ソースから
     * キーセットでチャンクを取り、{@link NotificationBulkFanoutService#insertAndDispatchChunk} で
     * バルク INSERT ＋配信する。チャンクごとに {@link NotificationFanoutJobService#advanceCursor} で
     * カーソルを前進コミットし、空ページで {@code DONE}。失敗はリトライ／上限超で {@code DEAD_LETTER}。
     *
     * <p><b>例外契約</b>: 受信者解決／配信の失敗は本メソッド内で捕捉し
     * {@link NotificationFanoutJobService#recordFailure} に落とす（呼び出し側へ伝播しない＝耐久キューの正常動作）。
     * 失敗は握り潰さず {@code last_error}／{@code status} に残して可視化する。</p>
     */
    public void processOne(NotificationFanoutJob job) {
        FanoutRecipientSource source = recipientSourceRegistry.resolve(job.getScopeType())
                .orElseThrow(() -> new IllegalStateException(
                        "未登録の fan-out scope_type: " + job.getScopeType() + "（jobId=" + job.getId() + "）"));
        jobService.markRunning(job.getId());
        try {
            while (true) {
                long cursor = job.getCursorSubjectId();
                // 常に 4 引数版で受信者供給する。VILLAGE / TEAM は既定実装が include_supporters を無視して
                // 3 引数版へ委譲するため挙動不変。ORGANIZATION のみトグルを keyset クエリへ運搬する（Wave-2）。
                // include_supporters は非 NULL 列（DEFAULT TRUE）。防御的に NULL は true 扱い（旧 VILLAGE 行の全員配信を保つ）。
                boolean includeSupporters = !Boolean.FALSE.equals(job.getIncludeSupporters());
                // シャード対応 6 引数版で受信者供給する（CMP-001⑤）。
                // 各シャードジョブ（shard_index=0..N-1・shard_count=N）は自分のモジュロ区画
                // （subject_id % shardCount == shardIndex）だけを配信し、シャード間で重複しない。
                // shard_count=1 のジョブは 6 引数既定実装がそのまま 4 引数版へ委譲＝既存挙動不変。
                List<Long> page = source.nextPage(job.getScopeRef(), cursor, CHUNK_SIZE, includeSupporters,
                        job.getShardIndex(), job.getShardCount());
                if (page.isEmpty()) {
                    jobService.markDone(job.getId());
                    break;
                }
                // P1 バルク INSERT ＋ チャンクコミット ＋ 専用プール配信を再利用。
                // 通知行の per-row スコープは現行の村行事還流と同一（SYSTEM / scopeId=null）。
                bulkFanoutService.insertAndDispatchChunk(
                        page,
                        job.getNotificationType(), job.getPriority(),
                        job.getTitle(), job.getBody(),
                        job.getSourceType(), job.getSourceId(),
                        NotificationScopeType.SYSTEM, null,
                        job.getActionUrl(), job.getActorId(), job.getOrganizationId());

                long newCursor = page.get(page.size() - 1);
                long added = page.size();
                // in-memory カーソル前進（ループ終了条件）＋ 独立コミットで耐久化（クラッシュ再開の要・AC-2）。
                job.setCursorSubjectId(newCursor);
                jobService.advanceCursor(job.getId(), newCursor, added);
            }
        } catch (Exception ex) {
            // 配信失敗＝リトライ／DEAD_LETTER へ落とす（耐久キューの正常動作。握り潰しではなく状態に残す）。
            log.warn("fan-out ジョブ配信失敗: jobId={} scopeType={} scopeRef={} cursor={}",
                    job.getId(), job.getScopeType(), job.getScopeRef(), job.getCursorSubjectId(), ex);
            jobService.recordFailure(job.getId(), ex.getClass().getSimpleName() + ": " + ex.getMessage(), MAX_RETRY);
        }
    }
}
