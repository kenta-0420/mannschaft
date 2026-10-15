package com.mannschaft.app.match.catalog;

import com.mannschaft.app.match.domain.MatchEventType;

import java.util.EnumSet;
import java.util.List;
import java.util.Set;

/**
 * フットサル競技カタログ（{@code Sport.FUTSAL} の中身）。
 *
 * <p>コアの拡張点 {@link SportEventCatalog}（01 §D.3・案 A＝enum＋コード定数カタログ）の
 * {@code Sport.FUTSAL} 実体。</p>
 *
 * <p><b>サッカーとの差分は小さい</b>（sports/02_futsal.md §2）。
 * フットサルはサッカーと同一の {@link MatchEventType} 集合を用いる。
 * 累積ファウル・タイムアウトは {@code OTHER}＋{@code custom_label} で任意記録する（MVP）。</p>
 *
 * <p>設計: docs/features/F08.10_match_record_analytics/sports/02_futsal.md §2 / §7</p>
 */
public final class FutsalCatalog {

    private FutsalCatalog() {
    }

    /**
     * フットサルで利用可能な event_type 集合（正準 = sports/02_futsal.md §2）。
     *
     * <p>サッカーと<b>完全に同一</b>の集合を用いる。
     * {@link SoccerCatalog#EVENT_TYPES} と同じ値集合を独立した定数として定義することで、
     * FUTSAL カタログが将来サッカーと diverge した場合にも影響を局所化できる。</p>
     */
    public static final Set<MatchEventType> EVENT_TYPES = java.util.Collections.unmodifiableSet(
            EnumSet.of(
                    MatchEventType.STARTER,
                    MatchEventType.SUB_IN,
                    MatchEventType.SUB_OUT,
                    MatchEventType.GOAL,
                    MatchEventType.ASSIST,
                    MatchEventType.OWN_GOAL,
                    MatchEventType.PENALTY_GOAL,
                    MatchEventType.PENALTY_MISS,
                    MatchEventType.PENALTY_SHOOTOUT,
                    MatchEventType.YELLOW_CARD,
                    MatchEventType.RED_CARD,
                    MatchEventType.SECOND_YELLOW,
                    MatchEventType.SAVE,
                    MatchEventType.INJURY,
                    MatchEventType.PERIOD_START,
                    MatchEventType.PERIOD_END,
                    MatchEventType.OTHER));

    /**
     * フットサルのポジション語彙（大分類・正準 = sports/02_futsal.md §7）。
     *
     * <p>{@code player_appearances.position} の必須語彙。
     * 細分は任意で自由文字列として入れてよい。</p>
     */
    public static final List<String> POSITIONS = List.of("GK", "FIXO", "ALA", "PIVO");
}
