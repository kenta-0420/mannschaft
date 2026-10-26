package com.mannschaft.app.match.domain;

/**
 * 採点競技（フィギュアスケート/体操）の<b>種目/セグメント</b>
 * （sports/07_scored.md §4B / §10・第 4 状態モデル類型 SCORED）。
 *
 * <p>{@code match_scored_components.apparatus}（VARCHAR(32)・{@code @Enumerated(STRING)}）に格納される。
 * フィギュアは「種目」を持たず<b>セグメント（SP/FS）</b>で束ね、体操は<b>種目（apparatus）</b>で束ねる。
 * 競技別に許容される値が異なり、その対応は {@link com.mannschaft.app.match.catalog.ScoredComponentCatalog}
 * で定義する（当該競技のカタログ外の値は 400・症状を隠さない・§4B.2 / §10）。</p>
 *
 * <p><b>フィギュア（セグメント）</b>: {@link #SP}（ショートプログラム）/ {@link #FS}（フリースケーティング）。</p>
 * <p><b>体操（種目・男子 6・女子 4）</b>: {@link #FLOOR}（床）/ {@link #POMMEL_HORSE}（あん馬）/ {@link #RINGS}（つり輪）/
 * {@link #VAULT}（跳馬）/ {@link #PARALLEL_BARS}（平行棒）/ {@link #HORIZONTAL_BAR}（鉄棒）/
 * {@link #UNEVEN_BARS}（段違い平行棒）/ {@link #BALANCE_BEAM}（平均台）。</p>
 *
 * <p>設計: docs/features/F08.10_match_record_analytics/sports/07_scored.md §2 / §4B / §10</p>
 */
public enum ScoredApparatus {

    // ── フィギュアスケート（セグメント） ──
    /** ショートプログラム（フィギュア）。 */
    SP,
    /** フリースケーティング（フィギュア）。 */
    FS,

    // ── 体操（種目・男子） ──
    /** 床（FX・男女）。 */
    FLOOR,
    /** あん馬（PH・男子）。 */
    POMMEL_HORSE,
    /** つり輪（SR・男子）。 */
    RINGS,
    /** 跳馬（VT・男女）。 */
    VAULT,
    /** 平行棒（PB・男子）。 */
    PARALLEL_BARS,
    /** 鉄棒（HB・男子）。 */
    HORIZONTAL_BAR,

    // ── 体操（種目・女子） ──
    /** 段違い平行棒（UB・女子）。 */
    UNEVEN_BARS,
    /** 平均台（BB・女子）。 */
    BALANCE_BEAM
}
