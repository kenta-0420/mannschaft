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
     * @param jobName バッチ識別子（{@code @BatchEndpoint#name()} 相当）
     * @param skipped 今回フラグ無効でスキップしたなら {@code true}、本体を実行したなら {@code false}
     * @param reason  記録に残す理由（無効だったフラグキーを含む人間可読の文字列）
     * @return 実際に記録を書いたなら {@code true}
     */
    public boolean recordIfStateChanged(String jobName, boolean skipped, String reason) {
        Boolean previous = lastSkipped.put(jobName, skipped);

        boolean changed = (previous == null) ? skipped : (previous != skipped);
        if (!changed) {
            return false;
        }

        String message = (reason != null && !reason.isBlank())
                ? reason
                : (skipped
                        ? "フィーチャーフラグが無効のためスキップしました"
                        : "フィーチャーフラグが有効化されたため実行を再開しました");

        batchJobLogService.recordFeaturePolicyOutcome(jobName, skipped, message);
        log.info("BackgroundFeaturePolicy 状態遷移: jobName={}, skipped={}, reason={}",
                jobName, skipped, message);
        return true;
    }
}
