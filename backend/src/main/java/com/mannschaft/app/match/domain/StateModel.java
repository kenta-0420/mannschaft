package com.mannschaft.app.match.domain;

/**
 * 状態モデル類型（F08.10 コア・<b>競技非依存の器</b>・01 §D.6）。
 *
 * <p>多競技を「競技ごとに個別実装」せず、<b>3 つの状態モデル類型に抽象化</b>し、コアの分岐
 * （タイマー起動可否・出場時間算出・COMPLETED バリデーション・FE composable 選択）を
 * <b>類型単位</b>で行う。新競技は所属類型を宣言するだけでコアの大半を再利用できる（保守性の核）。</p>
 *
 * <p>{@code matches.state_model}（VARCHAR・{@code @Enumerated(STRING)}・既定 'CONTINUOUS_TIME'）に格納される。
 * {@link Sport} から導出可能だが、Service/FE の分岐を冪等かつ高速に行うため列としても保持する
 * （{@link Sport#stateModel()} が正準マッピング）。</p>
 *
 * <table border="1">
 *   <caption>3 類型（01 §D.6）</caption>
 *   <tr><th>類型</th><th>対象競技（MVP）</th><th>スコア表現</th><th>出場時間算出</th><th>period</th></tr>
 *   <tr><td>CONTINUOUS_TIME</td><td>SOCCER/FUTSAL/BASKETBALL</td><td>スカラ home/away_score（＋PK）</td>
 *       <td>区間合計（02 §E.1）</td><td>FIRST_HALF../QUARTER_1..（必須）</td></tr>
 *   <tr><td>SET_BASED</td><td>VOLLEYBALL</td><td>match_sets＋獲得セット数</td>
 *       <td>セット出場（分概念希薄）</td><td>SET_1..SET_5（必須）</td></tr>
 *   <tr><td>TURN_BASED</td><td>SHOGI/GO</td><td>勝敗＋勝ち方（§D.7・home/away_score=1-0/0-1/0-0）</td>
 *       <td><b>算出しない</b>（出場交代概念なし）</td><td><b>NULL</b>（ピリオド無）</td></tr>
 * </table>
 *
 * <p>設計: docs/features/F08.10_match_record_analytics/01_domain_and_ddl.md §D.6</p>
 */
public enum StateModel {

    /**
     * 連続時間制（SOCCER/FUTSAL の前後半・BASKETBALL の 4Q＋OT）。
     * タイマー＋ピリオドで進行。スコアはスカラ（PK 戦は別列）。出場時間を区間合計で算出。
     */
    CONTINUOUS_TIME,

    /**
     * セット制（VOLLEYBALL・best-of-5・デュース）。
     * {@code match_sets} 子表（§B.5）でセット得点を持ち、{@code home/away_score} は獲得セット数。
     */
    SET_BASED,

    /**
     * ターン制（SHOGI/GO・総手数・ピリオド無）。
     * スコア（連続量）を持たず、勝敗（home/away_score=1-0/0-1/0-0）＋勝ち方（win_method）で確定する（§D.7）。
     * 出場交代の概念が無いため出場時間算出は起動しない。
     */
    TURN_BASED
}
