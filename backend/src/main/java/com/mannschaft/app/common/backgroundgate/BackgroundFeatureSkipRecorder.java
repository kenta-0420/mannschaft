package com.mannschaft.app.common.backgroundgate;

import com.mannschaft.app.admin.BatchJobStatus;
import com.mannschaft.app.admin.entity.BatchJobLogEntity;
import com.mannschaft.app.admin.service.BatchJobLogService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * フラグ無効によるバッチのスキップを {@code batch_job_logs} へ記録する（Gate 基盤工事④-A）。
 *
 * <h2>「状態が変わった時だけ記録する」（マスター裁可）</h2>
 * <p>毎分走るバッチをβ期間中ずっと無効にしておくと、素朴に毎回記録した場合
 * {@code batch_job_logs} がスキップ行で埋まり、本当に見たい実行履歴が読めなくなる。
 * よって<b>スキップ→実行・実行→スキップの変わり目でのみ</b> 1 行記録する。
 * 「今スキップ中である」ことは直近 1 行を見れば分かり、情報は失われない。</p>
 *
 * <h2>前回状態は {@code batch_job_logs} が唯一の正である（マスター裁可・設計変更）</h2>
 * <p>当初はインメモリに前回状態を保持していたが、その方式は
 * <b>付属物が新たな欠陥を産み続けた</b>（Codex 検分で 3 巡）。
 * 「保存に失敗した遷移を落とさない」ために保留を足せば、保留の再失敗・状態反転時の欠落という
 * 穴が生まれ、保留を持つマップには上限と掃除が要り、掃除は確定済み状態を巻き添えにし、
 * 「読む→書く→確定する」を原子化するためにジョブ単位ロックが要る、という具合である。</p>
 *
 * <p>この記録は<b>「変わり目が読めること」だけを目的とした目印</b>であり、
 * それをメモリ上で完璧に守るために積み上げた機構の方が、守ろうとした情報より高くついていた。
 * よってインメモリ状態を全廃し、判定は毎回 {@code batch_job_logs} の当該ジョブの直近 1 行を
 * 読んで行う。索引 {@code idx_bjl_job (job_name, started_at DESC)} により
 * 1 行だけを引く読み取りであり（実測: {@code type=ref} / {@code rows=1} / filesort なし）、
 * ゲート対象バッチの実行ごとに 1 回だけ走る。</p>
 *
 * <h2>前回状態の読み取り方</h2>
 * <p>直近 1 行の {@code status} が {@link BatchJobStatus#SKIPPED} なら「前回はスキップしていた」、
 * それ以外（{@link BatchJobStatus#RESUMED} / SUCCESS / RUNNING / FAILED）なら「動いていた」と読む。
 * 行が 1 件も無ければ「動いていた」とみなす（起動直後に「実行中である」という無意味な行を
 * 全バッチ分積まないため。AC-9b）。</p>
 *
 * <p>スキップした回は本 Aspect が {@code @Order(5)} で最外周に居るため
 * {@code BatchExecutionAspect} が走らず、RUNNING/SUCCESS 行は生まれない。
 * したがって直近 1 行が SKIPPED のままであることが「今スキップ中」と一致する。</p>
 *
 * <h2>並行実行では同じ変わり目が 2 行書かれうる（許容する劣化・マスター裁可）</h2>
 * <p>DB が唯一の状態になるため、スケジュール実行と手動実行が同時に走ると、
 * 両方が同じ「直近 1 行」を読んで同じ変わり目を 2 行書きうる。
 * <b>この重複は許容する。</b> 記録の目的は変わり目が読めることであり、
 * 同じ変わり目が 2 行あっても読み手を欺かない（「いつ止まったか」「いつ再開したか」は正しく読める）。
 * <b>分散ロックは導入しない。</b> 目印 1 行の重複を防ぐために、
 * 全ノードをまたぐロックという可用性の単一障害点を持ち込む価値は無い。</p>
 *
 * <h2>記録の失敗はバッチの障害にしない</h2>
 * <p>本クラスは例外を外に出さない。記録は停止判断に付随する情報であり、その読み書きの失敗が
 * 停止判断そのものを「障害」に変えてはならない（詳細は
 * {@link #recordIfStateChanged(String, boolean, String)} の Javadoc）。</p>
 *
 * <h2>書けなかった遷移は再試行しない（設計上の割り切り）</h2>
 * <p>保存に失敗した遷移は保持しない。次回も同じ状態が続いていれば直近 1 行が変わっていないため
 * 自然に再試行されるが、次回までにフラグが元へ戻った場合、その一瞬の遷移は台帳に残らない。
 * <b>これは意図した割り切りである</b>: 台帳は「現在どちらの状態か」が直近 1 行から読めれば足り、
 * 書けなかった一瞬の往復を完全に保全するために保留機構を持つと、その機構自体が欠陥を産む
 * （上記のとおり実際に 3 巡した）。台帳の内容は常に「最後に書けた事実」と一致しており、嘘は残らない。</p>
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class BackgroundFeatureSkipRecorder {

    private final BatchJobLogService batchJobLogService;

    /**
     * 直近の記録と比べて状態が変わっていれば記録する。
     *
     * <h2>本メソッドは例外を投げない</h2>
     * <p>{@code batch_job_logs} が一時的に読めない・書けないことは起こりうるが、その例外を
     * {@link BackgroundFeaturePolicyAspect} まで伝播させると AC-2「例外を投げずに正常終了」に反し、
     * {@code BatchExecutionAspect} が FAILED を書いて {@code BatchFailedEvent} を飛ばす。つまり
     * <b>記録できなかっただけの回が「障害」として運用に通知される</b>。
     * 記録は停止判断に付随する情報であり、その失敗が停止判断そのものを障害に変えてはならない。
     * ここでの捕捉が正当なのはこの理由による。</p>
     *
     * <p><b>握り潰しではない。</b> 捕捉した例外は必ずスタックトレース付きで WARN に残す。
     * 読み取りに失敗した場合は<b>記録を諦めてバッチは正常に動く側へ倒す</b>
     * （前回状態が分からないまま書くと、連続スキップ中に毎回行を積む方向にも、
     * 変わり目を落とす方向にも倒れうる。どちらも台帳を汚すため、書かない方を選ぶ）。</p>
     *
     * @param jobName バッチ識別子（{@code @BatchEndpoint#name()} 相当）
     * @param skipped 今回フラグ無効でスキップしたなら {@code true}、本体を実行したなら {@code false}
     * @param reason  記録に残す理由（無効だったフラグキーを含む人間可読の文字列）
     * @return 実際に記録を書いたなら {@code true}
     */
    public boolean recordIfStateChanged(String jobName, boolean skipped, String reason) {
        boolean previouslySkipped;
        try {
            previouslySkipped = batchJobLogService.findLatestByJobName(jobName)
                    .map(BackgroundFeatureSkipRecorder::isSkipMarker)
                    .orElse(false);
        } catch (RuntimeException ex) {
            // 握り潰さない: スタックトレース付きで WARN に残す。
            // 前回状態が読めない以上、書くと台帳を汚す方向にしか倒れないため記録は諦める。
            // 再スローしないのは、記録の失敗を「バッチの障害」として誤報しないためである。
            log.warn("BackgroundFeaturePolicy の直近履歴を読めなかったため記録を見送る"
                    + "（バッチの実行判断そのものには影響しない）: jobName={}, skipped={}",
                    jobName, skipped, ex);
            return false;
        }

        if (skipped == previouslySkipped) {
            return false;
        }

        String message = describe(skipped, reason);
        try {
            batchJobLogService.recordFeaturePolicyOutcome(jobName, skipped, message);
        } catch (RuntimeException ex) {
            // 握り潰さない: スタックトレース付きで WARN に残す。再スローしない理由は上記 Javadoc のとおり。
            // 台帳が更新されていない以上、同じ状態が続いていれば次回の判定で自然に再試行される。
            log.warn("BackgroundFeaturePolicy 状態遷移の記録に失敗した: jobName={}, skipped={}, reason={}",
                    jobName, skipped, message, ex);
            return false;
        }

        log.info("BackgroundFeaturePolicy 状態遷移: jobName={}, skipped={}, reason={}",
                jobName, skipped, message);
        return true;
    }

    /**
     * その行が「スキップ中である」ことを表すか。
     *
     * <p>{@link BatchJobStatus#SKIPPED} だけが該当する。{@link BatchJobStatus#RESUMED} や
     * 通常実行の RUNNING/SUCCESS/FAILED はいずれも「動いていた」側である。</p>
     */
    private static boolean isSkipMarker(BatchJobLogEntity latest) {
        return latest.getStatus() == BatchJobStatus.SKIPPED;
    }

    /** 記録に残す文面。呼び出し側が理由を持たない場合（再開時など）に既定文へ倒す。 */
    private String describe(boolean skipped, String reason) {
        if (reason != null && !reason.isBlank()) {
            return reason;
        }
        return skipped
                ? "フィーチャーフラグが無効のためスキップしました"
                : "フィーチャーフラグが有効化されたため実行を再開しました";
    }
}
