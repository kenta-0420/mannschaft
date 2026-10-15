package com.mannschaft.app.match.domain;

/**
 * 採点競技（フィギュアスケート/体操）の採点内訳の<b>項目種別</b>
 * （sports/07_scored.md §4B / §10・第 4 状態モデル類型 SCORED）。
 *
 * <p>{@code match_scored_components.component_type}（VARCHAR(32)・{@code @Enumerated(STRING)}）に格納される。
 * 競技別に許容される値が異なり、その対応は {@link com.mannschaft.app.match.catalog.ScoredComponentCatalog}
 * で定義する（フィギュア=TES/PCS/DEDUCTION・体操=D_SCORE/E_SCORE）。当該競技のカタログ外の値は 400 で弾く
 * （症状を隠さない・§4B.2 / §10）。</p>
 *
 * <ul>
 *   <li>{@link #TES} — フィギュア技術点（要素ごとの基礎点＋GOE の合計セグメント値）。</li>
 *   <li>{@link #PCS} — フィギュア演技構成点（5 コンポーネントの合計）。</li>
 *   <li>{@link #DEDUCTION} — フィギュア減点（転倒等の Deductions・合計から差し引く負方向の項目）。</li>
 *   <li>{@link #D_SCORE} — 体操 D スコア（難度点・上限なし）。</li>
 *   <li>{@link #E_SCORE} — 体操 E スコア（実施点・10.000 から減点）。</li>
 * </ul>
 *
 * <p>設計: docs/features/F08.10_match_record_analytics/sports/07_scored.md §4B / §10</p>
 */
public enum ScoredComponentType {

    /** フィギュア技術点（TES＝Technical Element Score）。 */
    TES,

    /** フィギュア演技構成点（PCS＝Program Component Score）。 */
    PCS,

    /** フィギュア減点（Deductions・転倒等・合計から差し引く負方向の項目）。 */
    DEDUCTION,

    /** 体操 D スコア（難度点）。 */
    D_SCORE,

    /** 体操 E スコア（実施点）。 */
    E_SCORE
}
