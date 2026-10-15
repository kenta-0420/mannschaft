package com.mannschaft.app.match.catalog;

import com.mannschaft.app.match.domain.ScoredApparatus;
import com.mannschaft.app.match.domain.ScoredComponentType;
import com.mannschaft.app.match.domain.Sport;

import java.util.EnumSet;
import java.util.Map;
import java.util.Set;

/**
 * 採点競技（フィギュアスケート/体操）の<b>採点内訳の競技別カタログ</b>
 * （sports/07_scored.md §4B.2 / §10・第 4 状態モデル類型 SCORED）。
 *
 * <p>コアの拡張点（01 §D.3・案 A＝enum＋コード定数カタログ）の採点内訳版。競技ごとに許容される
 * {@link ScoredComponentType}（項目）と {@link ScoredApparatus}（種目/セグメント）の集合を定義し、
 * 当該競技のカタログ外の値を 400 で弾く（症状を隠さない・§4B.2 / §10）。</p>
 *
 * <ul>
 *   <li><b>フィギュア（FIGURE_SKATING）</b>: 項目＝TES/PCS/DEDUCTION・種目（セグメント）＝SP/FS。</li>
 *   <li><b>体操（GYMNASTICS）</b>: 項目＝D_SCORE/E_SCORE・種目＝FLOOR/POMMEL_HORSE/RINGS/VAULT/
 *       PARALLEL_BARS/HORIZONTAL_BAR/UNEVEN_BARS/BALANCE_BEAM。</li>
 * </ul>
 *
 * <p><b>採点競技以外</b>（SOCCER 等）は本カタログに登録しない（{@link #isScoredSport(Sport)} が false）。
 * 採点競技でない試合への内訳記録は Service が競技不一致として弾く（MATCH_029）。</p>
 *
 * <p>設計: docs/features/F08.10_match_record_analytics/sports/07_scored.md §4B.2 / §10</p>
 */
public final class ScoredComponentCatalog {

    private ScoredComponentCatalog() {
    }

    /**
     * 競技別の許容項目（component_type）集合（不変）。
     *
     * <p>フィギュア=TES/PCS/DEDUCTION・体操=D_SCORE/E_SCORE。両者で重複しないため
     * 「その項目がその競技のものか」を厳密に検証できる（フィギュアに D_SCORE を入れたら 400）。</p>
     */
    public static final Map<Sport, Set<ScoredComponentType>> COMPONENT_TYPES = Map.of(
            Sport.FIGURE_SKATING, java.util.Collections.unmodifiableSet(EnumSet.of(
                    ScoredComponentType.TES,
                    ScoredComponentType.PCS,
                    ScoredComponentType.DEDUCTION)),
            Sport.GYMNASTICS, java.util.Collections.unmodifiableSet(EnumSet.of(
                    ScoredComponentType.D_SCORE,
                    ScoredComponentType.E_SCORE)));

    /**
     * 競技別の許容種目/セグメント（apparatus）集合（不変）。
     *
     * <p>フィギュアはセグメント（SP/FS）、体操は種目（床/あん馬…）。apparatus は NULL 許容
     * （種目を区別せず合計のみ記録する内訳も許す）であり、本集合は「指定された場合に許容される値」を表す。</p>
     */
    public static final Map<Sport, Set<ScoredApparatus>> APPARATUSES = Map.of(
            Sport.FIGURE_SKATING, java.util.Collections.unmodifiableSet(EnumSet.of(
                    ScoredApparatus.SP,
                    ScoredApparatus.FS)),
            Sport.GYMNASTICS, java.util.Collections.unmodifiableSet(EnumSet.of(
                    ScoredApparatus.FLOOR,
                    ScoredApparatus.POMMEL_HORSE,
                    ScoredApparatus.RINGS,
                    ScoredApparatus.VAULT,
                    ScoredApparatus.PARALLEL_BARS,
                    ScoredApparatus.HORIZONTAL_BAR,
                    ScoredApparatus.UNEVEN_BARS,
                    ScoredApparatus.BALANCE_BEAM)));

    /** 当該競技が採点内訳カタログを持つ採点競技（SCORED）か。 */
    public static boolean isScoredSport(Sport sport) {
        return sport != null && COMPONENT_TYPES.containsKey(sport);
    }

    /**
     * 当該競技で {@code componentType} が許容されるか（カタログ列挙の検証・§4B.2 / §10）。
     *
     * @param sport         競技
     * @param componentType 項目（NULL は不許容＝必須）
     * @return 許容されれば true
     */
    public static boolean isComponentTypeAllowed(Sport sport, ScoredComponentType componentType) {
        if (componentType == null) {
            return false;
        }
        Set<ScoredComponentType> allowed = COMPONENT_TYPES.get(sport);
        return allowed != null && allowed.contains(componentType);
    }

    /**
     * 当該競技で {@code apparatus} が許容されるか（§4B.2 / §10）。
     *
     * <p>{@code apparatus} は NULL 許容（種目を区別しない内訳）であり、NULL は常に許容する。
     * 指定された場合のみ当該競技のカタログ列挙であることを要求する。</p>
     *
     * @param sport     競技
     * @param apparatus 種目/セグメント（NULL 許容）
     * @return 許容されれば true（NULL も true）
     */
    public static boolean isApparatusAllowed(Sport sport, ScoredApparatus apparatus) {
        if (apparatus == null) {
            return true;
        }
        Set<ScoredApparatus> allowed = APPARATUSES.get(sport);
        return allowed != null && allowed.contains(apparatus);
    }
}
