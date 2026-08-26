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
     * <h2>選ぶ前に確かめること — 「停止期間を跨いで再開したとき追いつけるか」</h2>
     * <p>本モードは<b>「止めても再開すれば取り戻せる」ことを暗黙の前提にしている</b>。
     * この前提が実装と一致していない例が ④-B 第三陣で複数見つかった。
     * <b>追いつけないなら {@link #ALWAYS} を選ぶこと。</b></p>
     *
     * <p>追いつけない典型:</p>
     * <ul>
     *   <li><b>対象期間を {@code today} から導出する no-arg 入口しか無い</b> —
     *       {@code now.minusMonths(1)} 固定で、対象月を指定して再実行する経路が無い
     *       （{@code @BatchEndpoint} の手動実行も同じ no-arg を呼ぶだけなら「手動経路がある」とは言えない）。
     *       停止期間分は二度と生成できず恒久的な欠測になる。</li>
     *   <li><b>「現在の期間に一致する設定」しか拾わない</b> —
     *       設定された月を跨いで無効のままだと、その回の処理は永久に行われない。</li>
     *   <li><b>再開時に過去分をまとめて送ってしまう通知系</b> —
     *       未送信キューを下限なしで拾うと、停止明けに古い通知が一斉に飛ぶ。
     *       これは追いつけないのではなく<b>追いつきすぎる</b>害であり、
     *       多くはクエリ側に下限が無い潜在バグである（ゲートの有無に関わらず障害復帰でも起きる）。</li>
     * </ul>
     *
     * <p>逆に、{@code aggregateForDate(LocalDate)} や {@code closeAll(YearMonth)} のように
     * <b>対象期間を引数で受ける public な入口</b>があるなら、停止期間分は後から埋められるので本モードでよい。</p>
     *
     * <h2>迷ったら ALWAYS に倒す</h2>
     * <p>誤って {@link #ALWAYS} にした損は「閉じた機能の上でバッチが無害に空回りする」だけである。
     * 誤って本モードにした損は「法令や金が静かに壊れ、誰も気づかない」。
     * この二つは釣り合わないため、釣り合わない賭けでは安いほうの損を選ぶ。</p>
     *
     * <h2>テストでゲートを開ける経路は 3 つある（全部確かめること）</h2>
     * <p>本モードを宣言した入口は、テストでは<b>既定で閉じる</b>。
     * 「バッチが動くこと」を検証するテストが落ちたら、次の 3 経路を順に疑うこと
     * （④-B 第三陣は 3 つとも順番に踏み抜いた）。</p>
     * <ol>
     *   <li><b>DB 行</b> — {@code application-test.yml} は {@code flyway.enabled: false} +
     *       {@code ddl-auto: create} のため {@code feature_flags} の seed が走らず表が空。
     *       行が無いキーは {@code FeatureFlagService#isEnabled} がフェイルクローズで false を返す。</li>
     *   <li><b>キャッシュ</b> — {@code isEnabled} は {@code @Cacheable("featureFlags")}。
     *       {@code RedisConfig} が {@code CacheManager} を Bean 定義しているため
     *       {@code spring.cache.type: none} でも<b>キャッシュは有効なまま</b>で、
     *       先に走ったテストが載せた false を返し続ける。行の投入とキャッシュ退避は必ず対で行う
     *       （{@code FeatureFlagTestSupport} が両方やる）。</li>
     *   <li><b>モック</b> — {@code AbstractSpotlightIT} のように
     *       {@code @MockitoBean} で {@code FeatureFlagService} ごと差し替えている基底があると、
     *       DB もキャッシュも一切効かず Mockito 既定の false が返る。
     *       <b>葉のクラスだけを見ず継承階層まで確かめること</b>
     *       （第三陣はここを見落として 2 度同じ NPE を出した）。</li>
     * </ol>
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
