package com.mannschaft.app.auth.service;

import com.mannschaft.app.admin.batch.BatchEndpoint;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * F01.9 / F09.17: {@code users.birth_year} 一回限りの埋め戻し（移行）バッチ。
 *
 * <h2>背景</h2>
 * <p>{@code users.birth_year}（V68.004 追加）は、新規登録時の複写（PR #2614）が入る以前は
 * どの本番コード経路からも書き込まれておらず、既存行はすべて NULL である。本バッチは
 * {@code birth_date IS NOT NULL AND birth_year IS NULL} の既存行を対象に、復号済み
 * {@code birth_date} から生年を抽出して埋め戻す。</p>
 *
 * <h2>冪等性</h2>
 * <p>対象条件が {@code birth_year IS NULL} を含むため、既に埋まっている行は候補に含まれない。
 * 何度実行しても安全（同じ行を再更新しない）。</p>
 *
 * <h2>一回限りの移行バッチであり {@code @Scheduled} を付けない理由</h2>
 * <p>本バッチは新規登録時の複写ロジック導入前に生じた既存データのギャップを埋めるための
 * 一過性の移行作業であり、恒常的に発生し続ける現象への対処ではない（新規行は登録時点で
 * 埋まる）。定期実行の対象にすると「本来空振りし続けるはずのジョブ」が恒久的にスケジューラへ
 * 残り、運用上の意味のない負荷とノイズになる。{@link com.mannschaft.app.admin.batch.BatchEndpoint}
 * により管理画面から手動起動できるため、移行完了の確認・再実行はそちらで行う。</p>
 *
 * <h2>チャンク単位コミット・再開可能性</h2>
 * <p>1000 万ユーザー規模を見据え、{@code id} 昇順のキーセットページングでチャンク処理する
 * （{@code OFFSET} ページングは使わない）。各チャンクは {@link UserBirthYearBackfillChunkService}
 * の独立した {@code @Transactional} メソッドとして個別コミットするため、途中でプロセスが
 * 落ちても未処理カーソルから再開できる（全体を単一トランザクションで包まない）。</p>
 *
 * <h2>出口条件</h2>
 * <p>本バッチの完了後、{@code ParentalConsentReleaseBatchService} の候補抽出クエリ
 * （{@code ParentalConsentLinkRepository#findAdultCandidateLinksAfterId}）から
 * {@code birth_year IS NULL OR} の許容を外せるようになる（移行期のみの措置）。設計書
 * {@code docs/features/F01.9_age_verification_parental_consent.md} に出口条件を明記する。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class UserBirthYearBackfillBatchService {

    /** 1 チャンクあたりの取得件数。 */
    static final int PAGE_SIZE = 500;

    private final UserBirthYearBackfillChunkService chunkService;

    /**
     * {@code birth_year} 埋め戻しを完走するまでチャンク処理を繰り返すエントリポイント。
     *
     * <p>管理画面（{@code BatchEndpointRegistry}）からの手動起動を想定する。</p>
     */
    @BatchEndpoint(name = "user-birth-year-backfill-once",
            description = "既存ユーザーのbirth_yearをbirth_dateから復号して埋め戻す一回限りの移行バッチ（冪等・再実行可）")
    public void execute() {
        log.info("[UserBirthYearBackfill] 埋め戻しバッチ開始");

        long cursor = 0L;
        long totalProcessed = 0;
        long totalSuccess = 0;
        long totalFailed = 0;
        int chunkCount = 0;

        while (true) {
            UserBirthYearBackfillChunkService.ChunkResult result = chunkService.processChunk(cursor, PAGE_SIZE);
            if (result.processedCount() == 0) {
                break;
            }
            chunkCount++;
            cursor = result.newCursor();
            totalProcessed += result.processedCount();
            totalSuccess += result.successCount();
            totalFailed += result.failedCount();

            if (result.processedCount() < PAGE_SIZE) {
                break;
            }
        }

        log.info("[UserBirthYearBackfill] 埋め戻しバッチ完了: チャンク数={}, 処理件数={}, 成功={}, 失敗={}",
                chunkCount, totalProcessed, totalSuccess, totalFailed);
    }
}
