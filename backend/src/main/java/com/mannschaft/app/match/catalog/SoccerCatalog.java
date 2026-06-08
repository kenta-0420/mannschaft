package com.mannschaft.app.match.catalog;

import com.mannschaft.app.match.domain.MatchEventType;

import java.util.EnumSet;
import java.util.List;
import java.util.Set;

/**
 * サッカー競技カタログ（{@code Sport.SOCCER} の中身）。
 *
 * <p>コアの拡張点 {@link SportEventCatalog}（01 §D.3・案 A＝enum＋コード定数カタログ）の
 * {@code Sport.SOCCER} 実体。サッカーが利用可能な {@link MatchEventType} 集合・ポジション語彙を保持する。
 * コアに値を二重定義せず、サッカー固有の集合は本クラスに集約する（01 §D.3・正準は sports/01_soccer.md §2/§7）。</p>
 *
 * <p>設計: docs/features/F08.10_match_record_analytics/sports/01_soccer.md §2 / §7</p>
 */
public final class SoccerCatalog {

    private SoccerCatalog() {
    }

    /**
     * サッカーで利用可能な event_type 集合（正準 = sports/01_soccer.md §2）。
     * 不変集合として公開する。
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
     * サッカーのポジション語彙（大分類・正準 = sports/01_soccer.md §7）。
     * {@code player_appearances.position} の必須語彙。細分（CB/SB/DMF 等）は任意で自由文字列として入れてよい。
     */
    public static final List<String> POSITIONS = List.of("GK", "DF", "MF", "FW");
}
