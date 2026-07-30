package com.mannschaft.app.notification.fanout;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import net.javacrumbs.shedlock.core.LockAssert;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * 通知 fan-out 耐久ジョブの裏ワーカー（P2）。
 *
 * <p>一定間隔で PENDING ジョブを {@link NotificationFanoutJobRepository#findReady} で取得し
 *（{@code FOR UPDATE SKIP LOCKED}）、各ジョブを {@code cursor_subject_id} から再開して受信者を
 * チャンク配信する。ShedLock により複数 pod でも同時実行されない（email_outbox ワーカー前例）。</p>
 *
 * <p><b>試練（red）段階では本体は未実装。</b> {@link #processReady()} / {@link #processOne(NotificationFanoutJob)}
 * は {@link UnsupportedOperationException} を投げる。green 化（受信者チャンク配信・カーソル前進・リトライ／
 * DEAD_LETTER 遷移）は P2 出陣が行う。テストは {@code @Scheduled} を自動発火させず（test プロファイルは
 * {@code @EnableScheduling} 無効）、{@link #processReady()} / {@link #processOne} を直接呼ぶ。</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class NotificationFanoutWorker {

    /** 1 周回で取得するジョブ数の上限。 */
    static final int BATCH_SIZE = 20;

    private final NotificationFanoutJobRepository jobRepository;
    private final FanoutRecipientSourceRegistry recipientSourceRegistry;
    private final NotificationFanoutJobService jobService;

    /**
     * 5 秒間隔で PENDING ジョブを 1 周回処理する。
     *
     * <p>{@code poll} 全体を 1 トランザクションで包むと per-job の独立コミット（再開・リトライの確定）が
     * 巻き戻るため、外側 TX を張らない契約を {@code NEVER} で固定する（email_outbox ワーカー前例）。</p>
     */
    @Scheduled(fixedDelay = 5_000)
    @SchedulerLock(name = "notificationFanoutWorker", lockAtMostFor = "PT5M", lockAtLeastFor = "PT5S")
    @Transactional(propagation = Propagation.NEVER)
    public void poll() {
        LockAssert.assertLocked();
        processReady();
    }

    /**
     * PENDING ジョブを {@link NotificationFanoutJobRepository#findReady} で取得し、各ジョブを
     * {@link #processOne} で処理する（1 周回）。
     *
     * @implNote 試練（red）段階では未実装。P2 出陣で実装する。
     */
    public void processReady() {
        throw new UnsupportedOperationException(
                "NotificationFanoutWorker.processReady は P2 出陣で実装する（findReady→processOne の周回）");
    }

    /**
     * ジョブ 1 件を {@code cursor_subject_id} から再開し、{@link FanoutRecipientSourceRegistry} で解決した
     * 受信者ソースからチャンクを取り、{@link NotificationBulkFanoutService#insertAndDispatchChunk} で
     * バルク INSERT ＋配信する。チャンクごとに {@code cursor_subject_id} / {@code inserted_count} を前進コミットし、
     * 完了で {@code DONE}、失敗はリトライ（バックオフ）／上限超で {@code DEAD_LETTER} に遷移する。
     *
     * @implNote 試練（red）段階では未実装。P2 出陣で実装する。
     */
    public void processOne(NotificationFanoutJob job) {
        throw new UnsupportedOperationException(
                "NotificationFanoutWorker.processOne は P2 出陣で実装する"
                        + "（cursor 再開・チャンク配信・リトライ／DEAD_LETTER 遷移）");
    }
}
