package com.mannschaft.app.match.catalog;

/**
 * 将棋の勝ち方（{@code matches.win_method} に保持する競技別 enum・sports/05_shogi.md §4.1）。
 *
 * <p>コア §D.7（ターン制の勝ち方 enum＝競技別カタログ）に対する将棋の具体値。
 * 勝敗（どちらが勝ったか）は {@code home_score}/{@code away_score} の大小、勝ち方（どう勝ったか）は
 * 本 enum、の責務分離（§4.2・§B.1.2）。引き分け（千日手/持将棋）は {@code win_method}=NULL＋
 * 両スコア 0 で表現するため、本 enum に「引き分け」値は持たない。</p>
 *
 * <p>保守方針: 日本将棋連盟の対局規定（出典: https://www.shogi.or.jp/）に準拠。</p>
 *
 * <p>設計: docs/features/F08.10_match_record_analytics/sports/05_shogi.md §4.1
 *   / 01_domain_and_ddl.md §D.7</p>
 */
public enum ShogiWinMethod {

    /** 投了（最も一般的）。 */
    RESIGNATION,

    /** 詰み（実戦で詰みまで指す）。 */
    CHECKMATE,

    /** 時間切れ（持ち時間切れ）。 */
    TIMEOUT,

    /** 反則勝ち（相手の二歩・王手放置・打ち歩詰め等の反則による）。 */
    FOUL_WIN,

    /** 千日手（同一局面 4 回・指し直し or 規定により決着）。 */
    REPETITION,

    /** 持将棋（入玉宣言法 等・点数計算で決着 or 引き分け）。 */
    IMPASSE,

    /** 不戦勝（相手の不出場）。 */
    DEFAULT_WIN
}
