package com.mannschaft.app.common.backgroundgate;

/**
 * Gate 基盤工事④-A: バックグラウンド入口（バッチ・イベントリスナー）の「停止時挙動」。
 *
 * <p><b>判定軸は「β機能かどうか」ではなく「その処理を止めた結果、既存データの整合性が壊れるか」</b>である。
 * 入口（画面・API）を閉じても、停止前に保存されたデータや発行済みイベントは処理し切らねばならないため、
 * 「未公開機能に属するか」で機械的に決めてはならない。</p>
 *
 * <p>試練の骨格。実際の分岐は出陣で {@link BackgroundFeaturePolicyAspect} に実装する。</p>
 */
public enum BackgroundFeatureMode {

    /**
     * フラグに関わらず<b>必ず実行</b>する。
     *
     * <p>GDPR 削除・監査記録・課金整合・outbox 送信・リトライ・補償処理など、
     * 止めると既存データの整合性が壊れる処理に用いる。</p>
     *
     * <p>本モードでは {@code gateKeys} の指定は<b>禁止</b>（判定そのものを行わないため、
     * キーを書くと「ゲートされている」という誤読を生む）。{@code reason} は必須。</p>
     */
    ALWAYS,

    /**
     * フラグ無効時は本体を呼ばず<b>正常終了</b>する（{@code @Scheduled} 専用）。
     *
     * <p>例外を投げてはならない。バッチで例外を投げると
     * {@code BatchExecutionAspect} が {@code batch_job_logs} に FAILED を書き
     * {@code BatchFailedEvent} を飛ばすため、「意図した停止」が「障害」として運用に通知される。</p>
     */
    SKIP_WHEN_DISABLED,

    /**
     * フラグ無効時はイベントを<b>捨てる</b>（Spring イベントリスナー専用）。
     *
     * <p><b>イベントは再生されず失われる</b>。この消失を許容できる根拠を {@code reason} に明示すること。</p>
     *
     * <p>例外を投げてはならない。本番の {@code @TransactionalEventListener} は 143 件すべてが
     * {@code phase = AFTER_COMMIT} であり、このフェーズで投げた例外は Spring が握り潰す
     * （ログにすら出ない）。拒否を例外で表現すると「拒否したつもりが黙って通っている」事故と
     * 区別が付かなくなる。</p>
     */
    DROP_WHEN_DISABLED
}
