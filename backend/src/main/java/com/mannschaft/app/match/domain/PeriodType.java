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
 *   <li>セット制（バレーの SET_1..SET_5）は match_sets 子表と対応する補助値（sports/04_volleyball.md §3）。</li>
 *   <li>ターン制（将棋/囲碁）は period を使わない（{@code match_events.period} は NULL・01 §D.6）。</li>
 * </ul>
 *
 * <p>設計: docs/features/F08.10_match_record_analytics/01_domain_and_ddl.md §D.1
 *   / sports/01_soccer.md §3</p>
 */
public enum PeriodType {
    // --- サッカー/フットサル（競技固有の利用は sports/01_soccer.md §3 / sports/02_futsal.md §3） ---
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

    // --- 連続時間制・クォーター（バスケ・sports/03_basketball.md §3） ---
    QUARTER_1,
    QUARTER_2,
    QUARTER_3,
    QUARTER_4,
    OVERTIME,

    // --- セット制（バレー・sports/04_volleyball.md §3） ---
    SET_1,
    SET_2,
    SET_3,
    SET_4,
    SET_5
}
