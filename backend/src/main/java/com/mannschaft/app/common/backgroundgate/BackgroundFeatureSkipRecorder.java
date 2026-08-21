package com.mannschaft.app.common.backgroundgate;

import com.mannschaft.app.admin.service.BatchJobLogService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
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
 * <p>記録が 1 件も無いジョブに対する {@code skipped=false} は変わり目ではない（AC-9b）。
 * 起動直後に「実行中である」という無意味な行を全バッチ分積まないためである。
 * 逆に初回の {@code skipped=true} は「動いていたものが止まった」と等価に扱い記録する（AC-8）。</p>
 *
 * <h2>状態は「最後に保存できた値」＋「保存に失敗した遷移」の2つで持つ</h2>
 * <p>前回状態だけでは、保存に失敗した遷移がその後の状態反転で消える（Codex 検分2巡目 P2-2）。
 * 例: 停止（{@code true}）の保存が失敗した直後に再有効化されると、次の {@code false} は
 * 「初期状態と同じ」と判定され、<b>停止と再開の両方が永久に欠落</b>する。
 * よって失敗した遷移を保留（pending）として保持し、次回の状態に関わらず必ず再試行する。</p>
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

    /**
     * 追跡するジョブ数の上限（防御的な歯止め）。
     *
     * <p>{@code jobName} は {@code @BatchEndpoint#name()} かクラス名＋メソッド名であり、
     * <b>コンパイル時に決まる有限の集合</b>（宣言されたバッチの数）である。外部入力ではないため
     * 本来は無制限に増えない。それでも上限を置くのは、将来 jobName の作り方が変わったときに
     * 静かにメモリを食い潰す経路を残さないためである。</p>
     */
    static final int MAX_TRACKED_JOBS = 1024;

    /** ジョブ単位の状態。<b>このインスタンス自身をジョブ単位のロックとして使う</b>。 */
    private static final class JobState {
        /** 最後に保存に成功した値。{@code null} は「まだ 1 件も記録していない」＝実行中とみなす。 */
        private Boolean lastRecorded;
        /** 保存に失敗し、次回に再試行すべき遷移の値。{@code null} なら保留なし。 */
        private Boolean pending;
        /** 保留中の遷移に添える理由（保存時の文面をそのまま持ち越す）。 */
        private String pendingReason;
    }

    /** ジョブ名 → 状態。値オブジェクトがそのままジョブ単位のロックを兼ねる。 */
    private final Map<String, JobState> states = new ConcurrentHashMap<>();

    private final BatchJobLogService batchJobLogService;

    /** 1 回の呼び出しで書きうる 1 件分の記録。 */
    private record Entry(boolean skipped, String reason) {
    }

    /**
     * 直近の結果と比べて状態が変わっていれば記録する。
     *
     * <h2>ジョブ単位で直列化する（Codex 検分2巡目 P2-1）</h2>
     * <p>「読む→保存する→確定する」は 1 つの不可分な操作である。{@code ConcurrentHashMap} は
     * <b>個々の操作しか原子化しない</b>ため、これを素の {@code get} と {@code put} に割ると、
     * スケジュール実行と手動実行が同じジョブを同時に叩いたとき両者が同じ前回状態を読み、
     * 同一の遷移を二重に保存する（実測: 16 スレッドで 16 行）。
     * よって {@code JobState} インスタンスを monitor として <b>ジョブ単位で</b>直列化する。</p>
     *
     * <p><b>グローバルロックにはしない。</b> 全ジョブを 1 つのロックで詰まらせると、
     * 1 ジョブの DB 遅延が無関係なバッチ全部を待たせる。
     * <b>{@code ConcurrentHashMap#compute} の中で保存を行うのも禁忌</b>である
     * （マップのビンをロックしたまま DB I/O を待つことになり、同じマップを触る
     * 他ジョブまで巻き添えにする）。マップ操作は {@code computeIfAbsent} による
     * 状態オブジェクトの取得だけに留め、保存はその外側の {@code synchronized} で行う。</p>
     *
     * <h2>記録の失敗は隔離する</h2>
     * <p>本メソッドは<b>いかなる場合も例外を投げない</b>。{@code batch_job_logs} が一時的に
     * 書けないことは起こりうるが、その例外を {@link BackgroundFeaturePolicyAspect} まで伝播させると
     * AC-2「例外を投げずに正常終了」に反し、{@code BatchExecutionAspect} が FAILED を書いて
     * {@code BatchFailedEvent} を飛ばす。つまり<b>記録できなかっただけの回が「障害」として
     * 運用に通知される</b>。記録は停止判断に付随する情報であり、その失敗が停止判断そのものを
     * 障害に変えてはならない。ここでの捕捉が正当なのはこの理由による。</p>
     *
     * <p><b>握り潰しではない。</b> 捕捉した例外は必ずスタックトレース付きで WARN に残し、
     * 失敗した遷移を保留として保持して次回に再試行する（記録は失われず、遅れるだけである）。</p>
     *
     * <h2>1 回の呼び出しで書く行数は高々 2 件</h2>
     * <p>「保留中の遷移」＋「今回の遷移」しか積まない（保留は常に最新の 1 件で上書きされる）。
     * 保留がいくつも積み上がって溢れることは構造的に起こらない。</p>
     *
     * @param jobName バッチ識別子（{@code @BatchEndpoint#name()} 相当）
     * @param skipped 今回フラグ無効でスキップしたなら {@code true}、本体を実行したなら {@code false}
     * @param reason  記録に残す理由（無効だったフラグキーを含む人間可読の文字列）
     * @return 1 件以上を実際に保存できたなら {@code true}
     */
    public boolean recordIfStateChanged(String jobName, boolean skipped, String reason) {
        JobState state = acquireState(jobName);

        // ジョブ単位の直列化。保存（DB I/O）はここに含まれるが、ロックはこのジョブに閉じており
        // 他ジョブ・マップ全体を巻き込まない。
        synchronized (state) {
            List<Entry> todo = pendingAndCurrent(state, skipped, reason);
            if (todo.isEmpty()) {
                return false;
            }

            boolean wroteAny = false;
            for (Entry entry : todo) {
                try {
                    batchJobLogService.recordFeaturePolicyOutcome(
                            jobName, entry.skipped(), entry.reason());
                } catch (RuntimeException ex) {
                    // 握り潰さない: スタックトレース付きで WARN に残し、失敗した遷移を保留にする。
                    // ここで再スローしないのは、記録の失敗を「バッチの障害」として運用に誤報しないため
                    // （上記 Javadoc「記録の失敗は隔離する」を参照）。
                    state.pending = entry.skipped();
                    state.pendingReason = entry.reason();
                    log.warn("BackgroundFeaturePolicy 状態遷移の記録に失敗した（次回再試行する）: "
                            + "jobName={}, skipped={}, reason={}",
                            jobName, entry.skipped(), entry.reason(), ex);
                    return wroteAny;
                }

                // 保存に成功した時点で初めて確定させ、保留を解消する。
                state.lastRecorded = entry.skipped();
                state.pending = null;
                state.pendingReason = null;
                wroteAny = true;
                log.info("BackgroundFeaturePolicy 状態遷移: jobName={}, skipped={}, reason={}",
                        jobName, entry.skipped(), entry.reason());
            }
            return wroteAny;
        }
    }

    /**
     * 今回書くべき記録を「保留分 → 今回分」の順に組み立てる（高々 2 件）。
     *
     * <p>今回分を書くかどうかの比較相手は、保留があるならその値である。保留は
     * 「まだ保存できていないが、確かに起きた遷移」であり、次の遷移の起点になるためである。</p>
     */
    private List<Entry> pendingAndCurrent(JobState state, boolean skipped, String reason) {
        List<Entry> todo = new ArrayList<>(2);

        if (state.pending != null) {
            todo.add(new Entry(state.pending, state.pendingReason));
        }

        boolean baseline = (state.pending != null)
                ? state.pending
                // 未記録（null）は「実行中」とみなす。起動直後に無意味な行を積まないため。
                : (state.lastRecorded != null && state.lastRecorded);

        if (skipped != baseline) {
            todo.add(new Entry(skipped, describe(skipped, reason)));
        }
        return todo;
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

    /**
     * ジョブ単位の状態を取得する（無ければ作る）。
     *
     * <p>マッピング関数は<b>オブジェクトを1つ作るだけ</b>で、I/O も他のロック取得も行わない。
     * {@code ConcurrentHashMap} のビンを保持したままブロックしないための制約である。</p>
     */
    private JobState acquireState(String jobName) {
        JobState existing = states.get(jobName);
        if (existing != null) {
            return existing;
        }
        if (states.size() >= MAX_TRACKED_JOBS) {
            // 想定外（jobName の作り方が変わった等）。黙って膨らませず、まず気付けるようにする。
            // 再試行待ちを持たないエントリだけを捨てる（捨てても最悪 1 行余計に記録されるだけで、
            // 保留中の遷移＝まだ保存できていない情報は決して失わない）。
            log.error("BackgroundFeaturePolicy の追跡ジョブ数が上限 {} に達した。"
                    + "保留の無いエントリを整理する: jobName={}", MAX_TRACKED_JOBS, jobName);
            evictSettledEntries();
        }
        return states.computeIfAbsent(jobName, key -> new JobState());
    }

    /** 保留を持たない（＝失っても情報が消えない）エントリだけを取り除く。 */
    private void evictSettledEntries() {
        states.entrySet().removeIf(entry -> {
            JobState state = entry.getValue();
            synchronized (state) {
                return state.pending == null;
            }
        });
    }
}
