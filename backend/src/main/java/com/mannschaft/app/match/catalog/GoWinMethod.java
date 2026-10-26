package com.mannschaft.app.match.catalog;

/**
 * 囲碁の勝ち方（{@code matches.win_method} に保持する競技別 enum・sports/06_go.md §4.1）。
 *
 * <p>コア §D.7（ターン制の勝ち方 enum＝競技別カタログ）に対する囲碁の具体値。将棋と同じ責務分離
 * （勝敗は home/away_score、勝ち方は本 enum）。引き分け（持碁＝盤面同数）は {@code win_method}=NULL＋
 * 両スコア 0 で表現するため、本 enum に「引き分け」値は持たない。将棋との差分は、囲碁固有の
 * {@link #POINTS_WIN}（目数差勝ち）を持ち、千日手/持将棋を持たない点（各競技が自競技の勝ち方を引く）。</p>
 *
 * <p>目数差勝ち（{@link #POINTS_WIN}）の目数差（margin）は任意で {@code detail} に保持する（§2.1・§4.2）。
 * 中押し勝ち（投了）の場合は margin なし。</p>
 *
 * <p>保守方針: 日本棋院の対局規定（出典: https://www.nihonkiin.or.jp/）に準拠。</p>
 *
 * <p>設計: docs/features/F08.10_match_record_analytics/sports/06_go.md §4.1
 *   / 01_domain_and_ddl.md §D.7</p>
 */
public enum GoWinMethod {

    /** 投了（中押し勝ち＝ちゅうおしがち）。 */
    RESIGNATION,

    /** 目数差勝ち（盤面の地の差で決着・margin を任意保持）。 */
    POINTS_WIN,

    /** 時間切れ。 */
    TIMEOUT,

    /** 反則勝ち（着手禁止点・コウの即取り返し等の反則）。 */
    FOUL_WIN,

    /** 不戦勝（相手の不出場）。 */
    DEFAULT_WIN
}
