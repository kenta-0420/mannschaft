package com.mannschaft.app.common.backgroundgate;

import com.mannschaft.app.admin.service.BatchJobLogService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * フラグ無効によるバッチのスキップを {@code batch_job_logs} へ記録する（Gate 基盤工事④-A）。
 *
 * <h2>「状態が変わった時だけ記録する」（マスター裁可）</h2>
 * <p>毎分走るバッチをβ期間中ずっと無効にしておくと、素朴に毎回記録した場合
 * {@code batch_job_logs} がスキップ行で埋まり、本当に見たい実行履歴が読めなくなる。
 * よって<b>スキップ→実行・実行→スキップの変わり目でのみ</b> 1 行記録する。
 * 「今スキップ中である」ことは直近 1 行を見れば分かり、情報は失われない。</p>
 *
 * <h2>初期状態は「実行」とみなす</h2>
 * <p>直前の記録が無いジョブに対する {@code skipped=false} は変わり目ではない（AC-9b）。
 * 起動直後に「実行中である」という無意味な行を全バッチ分積まないためである。
 * 逆に初回の {@code skipped=true} は「動いていたものが止まった」と等価に扱い記録する（AC-8）。</p>
 *
 * <h2>記録の失敗はバッチの障害にしない</h2>
 * <p>本クラスは例外を外に出さない。記録は停止判断に付随する情報であり、その保存失敗が
 * 停止判断そのものを「障害」に変えてはならない（詳細は
 * {@link #recordIfStateChanged(String, boolean, String)} の Javadoc）。</p>
 *
 * <h2>状態はノードローカルである</h2>
 * <p>複数 Pod で走る場合、Pod ごとに独立した重複抑止となる（最悪でも Pod 数だけ行が増える）。
 * 分散ロックを持ち込むほどの価値は無く、記録の目的（変わり目が読めること）は満たされる。</p>
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class BackgroundFeatureSkipRecorder {

    /** 直前にスキップしていたか（ジョブ名ごと・ノードローカル）。 */
    private final Map<String, Boolean> lastSkipped = new ConcurrentHashMap<>();

    private final BatchJobLogService batchJobLogService;

    /**
     * 直近の結果と比べて状態が変わっていれば記録する。
     *
     * <h2>記録の失敗は隔離する（Codex 検分 P2）</h2>
     * <p>本メソッドは<b>いかなる場合も例外を投げない</b>。{@code batch_job_logs} が一時的に
     * 書けないことは起こりうるが、その例外を {@link BackgroundFeaturePolicyAspect} まで伝播させると
     * AC-2「例外を投げずに正常終了」に反し、{@code BatchExecutionAspect} が FAILED を書いて
     * {@code BatchFailedEvent} を飛ばす。つまり<b>記録できなかっただけの回が「障害」として
     * 運用に通知される</b>。記録は停止判断に付随する情報であり、その失敗が停止判断そのものを
     * 障害に変えてはならない。ここでの捕捉が正当なのはこの理由による。</p>
     *
     * <p><b>握り潰しではない。</b> 捕捉した例外は必ずスタックトレース付きで WARN に残す。
     * 加えて<b>状態を確定させない</b>ため、次回の呼び出しで同じ遷移の記録が再試行される
     * （記録は失われず、遅れるだけである）。</p>
     *
     * <h2>状態の確定は保存成功後（順序が要件である）</h2>
     * <p>{@code lastSkipped} を保存より先に書き換えると、保存に失敗した遷移は次回「状態変化なし」と
     * 判定され、<b>二度と記録されない</b>。よって確定は保存が成功した後に行う。</p>
     *
     * @param jobName バッチ識別子（{@code @BatchEndpoint#name()} 相当）
     * @param skipped 今回フラグ無効でスキップしたなら {@code true}、本体を実行したなら {@code false}
     * @param reason  記録に残す理由（無効だったフラグキーを含む人間可読の文字列）
     * @return 実際に記録を書いたなら {@code true}（記録に失敗した場合も {@code false}）
     */
    public boolean recordIfStateChanged(String jobName, boolean skipped, String reason) {
        // 読むだけ。ここで put してしまうと、保存に失敗した遷移が次回「変化なし」と判定され、
        // 再試行されないまま失われる（状態の確定は保存成功後に行う）。
        Boolean previous = lastSkipped.get(jobName);

        boolean changed = (previous == null) ? skipped : (previous != skipped);
        if (!changed) {
            return false;
        }

        String message = (reason != null && !reason.isBlank())
                ? reason
                : (skipped
                        ? "フィーチャーフラグが無効のためスキップしました"
                        : "フィーチャーフラグが有効化されたため実行を再開しました");

        try {
            batchJobLogService.recordFeaturePolicyOutcome(jobName, skipped, message);
        } catch (RuntimeException ex) {
            // 握り潰さない: スタックトレース付きで WARN に残し、状態も確定させない
            // （次回の呼び出しで同じ遷移の記録が再試行される）。
            // ここで再スローしないのは、記録の失敗を「バッチの障害」として運用に誤報しないためである
            // （上記 Javadoc「記録の失敗は隔離する」を参照）。
            log.warn("BackgroundFeaturePolicy 状態遷移の記録に失敗した（次回再試行する）: "
                    + "jobName={}, skipped={}, reason={}", jobName, skipped, message, ex);
            return false;
        }

        // 保存に成功した時点で初めて状態を確定させる。
        lastSkipped.put(jobName, skipped);
        log.info("BackgroundFeaturePolicy 状態遷移: jobName={}, skipped={}, reason={}",
                jobName, skipped, message);
        return true;
    }
}
