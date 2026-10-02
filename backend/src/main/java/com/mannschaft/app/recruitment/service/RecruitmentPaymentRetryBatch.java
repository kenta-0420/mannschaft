package com.mannschaft.app.recruitment.service;

import com.mannschaft.app.common.backgroundgate.BackgroundFeatureMode;
import com.mannschaft.app.common.backgroundgate.BackgroundFeaturePolicy;
import com.mannschaft.app.admin.batch.BatchEndpoint;
import com.mannschaft.app.recruitment.entity.RecruitmentCancellationRecordEntity;
import com.mannschaft.app.recruitment.repository.RecruitmentCancellationRecordRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

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
 * リトライ処理は1件ごとに独立しており（他レコードの状態を参照しない）、
 * 処理順序に依存するロジックは無いことをコードレビューで確認済み。</p>
 *
 * <h2>徴収の実体（F03.11.1）</h2>
 * <p>1 件分の徴収は {@link RecruitmentCancellationFeeRetryProcessor} へ委譲する。
 * 1 件の失敗が他件のコミットを巻き込まないよう、1 件 = 独立トランザクション
 * （{@code REQUIRES_NEW}）である必要があり、そのためには別 Bean であることが要る
 * （同一 Bean 内の自己呼び出しは Spring プロキシを経由せず伝播設定が効かない）。</p>
 *
 * <p>初回徴収とリトライで別ロジックを持たない。どちらも payment ドメインの単一入口
 * {@code ConnectChargeService#settleCancellationFee} を同じ引数で呼ぶ（設計書 §3.4 / §5.5）。
 * escrow の引き当てと経路判定は payment ドメインの内部で完結するため、
 * <b>本バッチ（recruitment ドメイン）は escrow を読まない</b>。1 件につき Stripe 呼び出しが
 * 1 回必要である以上、件数分のラウンドトリップは構造的に不可避であり、これは N+1 問題ではなく
 * 仕事の量そのものである（一括取得しても Stripe 呼び出しは減らない）。</p>
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
    private final RecruitmentCancellationFeeRetryProcessor retryProcessor;

    /**
     * 1時間ごとに実行。ShedLock で重複実行を防止。
     */
    @BackgroundFeaturePolicy(mode = BackgroundFeatureMode.ALWAYS,
            reason = "殿の裁定: Stripe の与信は期限で失効するため、止めている間に再試行の機会そのものが消える。遅延がそのまま復旧不能な取りはぐれになる")
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

            int success = retryProcessor.processChunk(chunk);
            totalProcessed += chunk.size();
            totalSuccess += success;
            totalFailed += chunk.size() - success;

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

}
