package com.mannschaft.app.recruitment.service;

import com.mannschaft.app.admin.batch.BatchEndpoint;
import com.mannschaft.app.recruitment.entity.RecruitmentCancellationRecordEntity;
import com.mannschaft.app.recruitment.repository.RecruitmentCancellationRecordRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * F03.11 Phase5a: キャンセル料の決済リトライバッチ。
 * payment_status = 'FAILED' かつリトライ回数3回未満のレコードを最大3回まで再試行する。
 * 仕様書 §11（バッチ一覧）参照。
 *
 * <h2>ページングの注意</h2>
 * <p>対象レコードの走査は <b>キーセットページング</b>（{@code id > cursor}）で行う。
 * ループ内で {@code processRetry} が {@code paymentRetryCount} をインクリメントするため、
 * リトライ上限（{@link #MAX_RETRY_COUNT}）に達した行はその場で絞り込み
 * （{@code paymentRetryCount < maxRetries}）から外れる。母集合が縮んでいくため、
 * OFFSET を「ページ番号を進める」方式にすると、縮んだ分だけ後続の行が OFFSET の網
 * から漏れて読み飛ばされる。<b>ページ0固定のドレイン方式にしてもいけない</b>
 * （上限未満のまま残る行はいつまでも絞り込みに残り続けるため、無限ループになる）。
 * カーソルを直前チャンクの最終 {@code id} まで前進させるキーセット方式のみが、
 * 縮む母集合でも取りこぼしなく・無限ループにもならず全件を走査できる。</p>
 *
 * <p>これに伴いソート順を {@code cancelledAt}（一意でない）から {@code id}（一意）へ
 * 意図的に変更している。{@code cancelledAt} はキーセットのカーソルとして使えない
 * （同一値の行が複数存在し得るため、カーソル前進の一意性を保証できない）。
 * リトライ処理は {@link #processRetry} で1件ごとに独立しており（他レコードの状態を
 * 参照せず、決済APIとの連携もレコード単位のスタブ実装）、処理順序に依存する
 * ロジックは無いことをコードレビューで確認済み。</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class RecruitmentPaymentRetryBatch {

    private static final int MAX_RETRY_COUNT = 3;
    private static final int CHUNK_SIZE = 50;

    /** 安全弁: 想定外の滞留でバッチが無限に回り続けることを防ぐループ回数上限。 */
    static final int MAX_PAGES = 200;

    private final RecruitmentCancellationRecordRepository cancellationRecordRepository;

    /**
     * 1時間ごとに実行。ShedLock で重複実行を防止。
     */
    @BatchEndpoint(name = "recruitment-payment-retry-hourly", description = "募集キャンセル料の決済 FAILED を毎時最大 3 回までリトライする")
    @Scheduled(fixedDelay = 60 * 60 * 1000L)
    @SchedulerLock(
            name = "recruitment-payment-retry-batch",
            lockAtLeastFor = "PT50M",
            lockAtMostFor = "PT2H"
    )
    public void run() {
        log.info("F03.11 決済リトライバッチ 開始");
        long cursor = 0L;
        int totalProcessed = 0;
        int totalSuccess = 0;
        int totalFailed = 0;
        int page = 0;

        for (; page < MAX_PAGES; page++) {
            List<RecruitmentCancellationRecordEntity> chunk =
                    cancellationRecordRepository.findFailedForRetryAfterId(
                            MAX_RETRY_COUNT, cursor, PageRequest.of(0, CHUNK_SIZE));
            if (chunk.isEmpty()) {
                break;
            }

            for (RecruitmentCancellationRecordEntity record : chunk) {
                boolean success = processRetry(record);
                totalProcessed++;
                if (success) totalSuccess++; else totalFailed++;
            }

            // カーソルを直前チャンクの最終 id まで前進させる（キーセットページング）
            cursor = chunk.get(chunk.size() - 1).getId();

            if (chunk.size() < CHUNK_SIZE) {
                break;
            }
        }

        if (page >= MAX_PAGES) {
            log.warn("F03.11 決済リトライバッチ: MAX_PAGES({})に到達し打ち切り。未処理の行が残っている可能性がある。処理済み件数={}",
                    MAX_PAGES, totalProcessed);
        }

        log.info("F03.11 決済リトライバッチ 完了: 処理件数={}, 成功={}, 失敗={}",
                totalProcessed, totalSuccess, totalFailed);
    }

    /**
     * 1件のキャンセル記録に対してリトライを実行する。
     * 決済API統合は F03.4 決済システムとの別途調整が必要なため、
     * 現フェーズではリトライカウントのみインクリメントし、ログに記録する（スタブ）。
     *
     * @return true: 決済成功（将来実装）/ false: 失敗またはスタブ
     */
    @Transactional
    public boolean processRetry(RecruitmentCancellationRecordEntity record) {
        try {
            log.info("F03.11 決済リトライ: recordId={}, retryCount={}/{}",
                    record.getId(), record.getPaymentRetryCount() + 1, MAX_RETRY_COUNT);

            // TODO: F03.4 決済APIとの統合実装
            // 現フェーズではスタブ: リトライカウントをインクリメントして記録
            record.incrementRetryCount();
            cancellationRecordRepository.save(record);

            // MAX到達時は管理者通知（TODO: 通知API統合）
            if (record.getPaymentRetryCount() >= MAX_RETRY_COUNT) {
                log.warn("F03.11 決済リトライ上限到達: recordId={}, userId={}, feeAmount={}",
                        record.getId(), record.getUserId(), record.getFeeAmount());
            }

            return false; // スタブのため常にfalse
        } catch (Exception e) {
            log.error("F03.11 決済リトライ エラー: recordId={}", record.getId(), e);
            return false;
        }
    }
}
