package com.mannschaft.app.cms.service;

import com.mannschaft.app.admin.batch.BatchEndpoint;
import com.mannschaft.app.cms.entity.BlogMediaR2DeleteRetryEntity;
import com.mannschaft.app.cms.repository.BlogMediaR2DeleteRetryRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

/**
 * ブログメディア R2 削除リトライ日次バッチ（Issue #2601 別任務）。
 *
 * <p>{@code BlogMediaOrphanCleanupRunner} が R2 削除に失敗した際に登録した
 * {@code blog_media_r2_delete_retries}（{@code status=PENDING}）を、{@code next_attempt_at} が
 * 到来した順に指数バックオフで再試行する。1 件の処理は {@link BlogMediaR2DeleteRetryRunner#retryOne}
 * に {@code REQUIRES_NEW} の独立トランザクションで委譲する（R2 削除はトランザクション外の取り消せない
 * 外部操作であるため、バッチ全体を単一トランザクションで包んではならない）。</p>
 *
 * <h3>ページングの注意</h3>
 * <p>対象レコードの走査は <b>キーセットページング</b>（{@code id > cursor}）で行う。
 * ループ本体が処理済みの行の {@code status} / {@code next_attempt_at} を書き換えるため母集合が
 * 走査中に縮む。OFFSET ページングでは縮んだ分だけ後続の行が読み飛ばされ、逆にページ 0 固定の
 * ドレイン方式ではバックオフで未来に進んだ行がいつまでも絞り込みに残り無限ループになる。
 * カーソルを直前チャンクの最終 {@code id} まで前進させるキーセット方式のみが、縮む母集合でも
 * 取りこぼしなく・無限ループにもならず全件を走査できる。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class BlogMediaR2DeleteRetryBatchService {

    private static final int CHUNK_SIZE = 50;

    /** 安全弁: 想定外の滞留でバッチが無限に回り続けることを防ぐループ回数上限。 */
    static final int MAX_PAGES = 200;

    private final BlogMediaR2DeleteRetryRepository retryRepository;
    private final BlogMediaR2DeleteRetryRunner retryRunner;
    private final Clock clock;

    /**
     * 日次実行。ShedLock で重複実行を防止。
     */
    @BatchEndpoint(name = "cms-blog-media-r2-delete-retry-daily", description = "孤立ブログメディアの R2 削除失敗を毎日 03:00 に指数バックオフで再試行する")
    @Scheduled(cron = "0 0 3 * * *")
    // 起動間隔は日次 03:00。前日分の孤立メディアクリーンアップ（02:00 実行）で新たに登録された
    // 失敗分を主対象とするため、蓄積量は限定的と見積もり 1 時間を上限とする。
    @SchedulerLock(name = "cmsBlogMediaR2DeleteRetry", lockAtLeastFor = "PT1M", lockAtMostFor = "PT1H")
    public void run() {
        log.info("R2削除リトライバッチ 開始");
        LocalDateTime now = LocalDateTime.now(clock);
        UUID cursor = null;
        int totalProcessed = 0;
        int page = 0;

        for (; page < MAX_PAGES; page++) {
            List<BlogMediaR2DeleteRetryEntity> chunk =
                    retryRepository.findPendingDueAfterId(now, cursor, PageRequest.of(0, CHUNK_SIZE));
            if (chunk.isEmpty()) {
                break;
            }

            for (BlogMediaR2DeleteRetryEntity retry : chunk) {
                retryRunner.retryOne(retry);
                totalProcessed++;
            }

            // カーソルを直前チャンクの最終 id まで前進させる（キーセットページング）
            cursor = chunk.get(chunk.size() - 1).getId();

            if (chunk.size() < CHUNK_SIZE) {
                break;
            }
        }

        if (page >= MAX_PAGES) {
            log.warn("R2削除リトライバッチ: MAX_PAGES({})に到達し打ち切り。未処理の行が残っている可能性がある。処理済み件数={}",
                    MAX_PAGES, totalProcessed);
        }

        log.info("R2削除リトライバッチ 完了: 処理件数={}", totalProcessed);
    }
}
