package com.mannschaft.app.match.domain;

/**
 * ピリオド種別（F08.10 コア・<b>競技非依存の器</b>）。
 *
 * <p>{@code match_events.period}（VARCHAR・{@code @Enumerated(STRING)}）に格納される。
 * enum 自体（器）はコアに置き、<b>どの値を使うかは競技別カタログが定義</b>する。</p>
 *
 * <ul>
 *   <li>サッカーが使う具体値（FIRST_HALF/SECOND_HALF/EXTRA_FIRST/EXTRA_SECOND/PENALTY_SHOOTOUT）
 *       → sports/01_soccer.md §3 が正準。</li>
 *   <li>多競技拡張（バスケの QUARTER_1..4 / OVERTIME 等）は各競技カタログが使う。</li>
 * </ul>
 *
 * <p>設計: docs/features/F08.10_match_record_analytics/01_domain_and_ddl.md §D.1
 *   / sports/01_soccer.md §3</p>
 */
public enum PeriodType {
    // --- サッカー（競技固有の利用は sports/01_soccer.md §3） ---
    /** 前半 */
    FIRST_HALF,
    /** 後半 */
    SECOND_HALF,
    /** 延長前半 */
    EXTRA_FIRST,
    /** 延長後半 */
    EXTRA_SECOND,
    /** PK 戦（分概念なし） */
    PENALTY_SHOOTOUT,

    // --- 多競技拡張（バスケ等・各競技カタログが使う period 値） ---
    QUARTER_1,
    QUARTER_2,
    QUARTER_3,
    QUARTER_4,
    OVERTIME
}
